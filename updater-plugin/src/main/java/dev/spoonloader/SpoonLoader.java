package dev.spoonloader;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SpoonLoader extends JavaPlugin implements CommandExecutor {
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]+");
    private static final long MAX_ZIP_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_SCRIPT_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_TOTAL_SCRIPT_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_SCRIPTS = 250;
    private static final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + "Spoon" + ChatColor.DARK_GRAY + "] " + ChatColor.RESET;

    private final AtomicBoolean updating = new AtomicBoolean(false);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getCommand("spoonupdate") != null) {
            getCommand("spoonupdate").setExecutor(this);
        }

        if (getConfig().getBoolean("update-on-start", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> startUpdate(Bukkit.getConsoleSender()), 40L);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("spoonloader.admin")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Only server operators can update Spoon.");
            return true;
        }
        startUpdate(sender);
        return true;
    }

    private void startUpdate(CommandSender sender) {
        String repository = getConfig().getString("repository", "OWNER/REPOSITORY").trim();
        String branch = getConfig().getString("branch", "main").trim();

        if (!isValidRepository(repository) || repository.equalsIgnoreCase("OWNER/REPOSITORY")) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "Set repository: owner/name in plugins/SpoonLoader/config.yml first.");
            return;
        }
        if (!isValidBranch(branch)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "The configured GitHub branch is not valid.");
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("Skript") == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Skript is not installed or enabled.");
            return;
        }
        if (!updating.compareAndSet(false, true)) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "An update is already running.");
            return;
        }

        sender.sendMessage(PREFIX + ChatColor.YELLOW + "Downloading the public Spoon pack from GitHub...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                UpdateResult result = downloadAndInstall(repository, branch);
                Bukkit.getScheduler().runTask(this, () -> {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "sk reload spoon");
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "sk reload spoon_modules");
                    sender.sendMessage(PREFIX + ChatColor.GREEN + "Installed " + result.scriptCount + " Spoon module(s) and reloaded them.");
                });
            } catch (Exception error) {
                getLogger().severe("Spoon update failed: " + error.getMessage());
                Bukkit.getScheduler().runTask(this, () ->
                        sender.sendMessage(PREFIX + ChatColor.RED + "Update failed. Your previous pack was kept; check the console."));
            } finally {
                updating.set(false);
            }
        });
    }

    private UpdateResult downloadAndInstall(String repository, String branch) throws Exception {
        Path data = getDataFolder().toPath().toAbsolutePath().normalize();
        Path work = data.resolve("work");
        Path staging = work.resolve("staging");
        Path archive = work.resolve("repository.zip");
        deleteTree(work);
        Files.createDirectories(staging);

        URI uri = URI.create("https://codeload.github.com/" + repository + "/zip/refs/heads/" + branch);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "SpoonLoader/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) {
                throw new IOException("GitHub returned HTTP " + response.statusCode());
            }
        }
        try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(archive)) {
            copyBounded(input, output, MAX_ZIP_BYTES, "GitHub ZIP is too large");
        }

        int scriptCount = extractPack(archive, staging);
        installStagedPack(staging, data);
        deleteTree(work);
        return new UpdateResult(scriptCount);
    }

    private int extractPack(Path archive, Path staging) throws IOException {
        int scriptCount = 0;
        long totalBytes = 0;
        boolean foundLoader = false;

        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                int firstSlash = name.indexOf('/');
                if (firstSlash < 0 || firstSlash == name.length() - 1) {
                    continue;
                }
                String relative = name.substring(firstSlash + 1);
                boolean loader = relative.equals("spoon.sk");
                boolean module = relative.startsWith("spoon_modules/") && relative.toLowerCase().endsWith(".sk");
                if (!loader && !module) {
                    continue;
                }

                Path destination = staging.resolve(relative).normalize();
                if (!destination.startsWith(staging)) {
                    throw new IOException("Unsafe path in GitHub ZIP");
                }
                Files.createDirectories(destination.getParent());
                try (OutputStream output = Files.newOutputStream(destination)) {
                    long written = copyBounded(zip, output, MAX_SCRIPT_BYTES, "A script is too large: " + relative);
                    totalBytes += written;
                }
                if (totalBytes > MAX_TOTAL_SCRIPT_BYTES) {
                    throw new IOException("The script pack is too large");
                }
                if (loader) {
                    foundLoader = true;
                } else {
                    scriptCount++;
                    if (scriptCount > MAX_SCRIPTS) {
                        throw new IOException("The pack contains too many scripts");
                    }
                }
            }
        }

        if (!foundLoader) {
            throw new IOException("The repository does not contain spoon.sk");
        }
        if (scriptCount == 0) {
            throw new IOException("The repository has no .sk files in spoon_modules");
        }
        return scriptCount;
    }

    private void installStagedPack(Path staging, Path data) throws IOException {
        Path plugins = getDataFolder().toPath().toAbsolutePath().normalize().getParent();
        if (plugins == null) {
            throw new IOException("Could not locate the server plugins folder");
        }
        Path scripts = plugins.resolve("Skript").resolve("scripts").normalize();
        Path targetLoader = scripts.resolve("spoon.sk");
        Path targetModules = scripts.resolve("spoon_modules");
        Path stagedLoader = staging.resolve("spoon.sk");
        Path stagedModules = staging.resolve("spoon_modules");
        Files.createDirectories(scripts);

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path backup = data.resolve("backups").resolve(stamp);
        if (Files.exists(targetLoader)) {
            Files.createDirectories(backup);
            Files.copy(targetLoader, backup.resolve("spoon.sk"), StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.exists(targetModules)) {
            copyTree(targetModules, backup.resolve("spoon_modules"));
        }

        try {
            Path loaderTemp = scripts.resolve("spoon.sk.new");
            Files.copy(stagedLoader, loaderTemp, StandardCopyOption.REPLACE_EXISTING);
            deleteTree(targetModules);
            copyTree(stagedModules, targetModules);
            Files.move(loaderTemp, targetLoader, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException installError) {
            if (Files.exists(backup.resolve("spoon.sk"))) {
                Files.copy(backup.resolve("spoon.sk"), targetLoader, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.exists(backup.resolve("spoon_modules"))) {
                deleteTree(targetModules);
                copyTree(backup.resolve("spoon_modules"), targetModules);
            }
            throw installError;
        }
    }

    private static long copyBounded(InputStream input, OutputStream output, long limit, String message) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException(message);
            }
            output.write(buffer, 0, read);
        }
        return total;
    }

    private static boolean isValidRepository(String repository) {
        return REPOSITORY.matcher(repository).matches() && !repository.contains("..");
    }

    private static boolean isValidBranch(String branch) {
        return !branch.isBlank() && branch.length() <= 150 && BRANCH.matcher(branch).matches()
                && !branch.contains("..") && !branch.startsWith("/") && !branch.endsWith("/");
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path)).normalize();
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private record UpdateResult(int scriptCount) {}
}
