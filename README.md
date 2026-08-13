# Spoon Script Pack

`spoon.sk` is the operator command and loader for a folder of Skript modules.
This starter pack includes `compressor.sk` as its first module.

## Repository layout

```text
spoon.sk
spoon_modules/
  compressor.sk
  another-script.sk
```

## Install

Copy the files to your server so the final layout is:

```text
plugins/Skript/scripts/spoon.sk
plugins/Skript/scripts/spoon_modules/compressor.sk
plugins/Skript/scripts/spoon_modules/another-script.sk
```

Then run this in the server console:

```text
sk reload spoon
sk reload spoon_modules
```

Operators can later run `/spoon reload` in-game to reload every `.sk` file in
`spoon_modules`.

## Public GitHub download

A public repository can be downloaded without a GitHub token. The complete ZIP
URL is:

```text
https://github.com/OWNER/REPOSITORY/archive/refs/heads/main.zip
```

Plain Skript does not provide a safe built-in HTTP downloader. `spoon.sk` can
load and reload files after they are present in the scripts folder. The included
`updater-plugin/SpoonLoader.jar` performs the automatic GitHub download.

## Automatic updates

1. Upload `updater-plugin/SpoonLoader.jar` to the server's `plugins` folder.
2. Start the server once.
3. Open `plugins/SpoonLoader/config.yml`.
4. Change `repository` to `YOUR-GITHUB-NAME/YOUR-REPOSITORY`.
5. Restart, or run `/spoonupdate` as an operator.

The updater downloads only `spoon.sk` and `.sk` files inside `spoon_modules`.
It keeps a backup, replaces the old pack, and then reloads both parts. A public
repository needs no access token.

Anyone who can push to the configured repository can make the Minecraft server
run code. Only grant repository write access to people you trust.

The updater uses a fixed repository and branch, stages the download first,
rejects paths outside the pack, limits download sizes, and keeps the previous
version if downloading fails.
