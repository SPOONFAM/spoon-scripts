# SpoonLoader updater plugin

This small Java plugin downloads a public GitHub repository, installs `spoon.sk`
and every `.sk` file under `spoon_modules`, then reloads the pack through Skript.

After installing `SpoonLoader.jar`, start the server once and edit:

```text
plugins/SpoonLoader/config.yml
```

Set `repository` to `your-github-name/your-repository`, restart the server, or
run `/spoonupdate` as an operator. Public repositories do not require a token.
The previous installed pack is backed up under `plugins/SpoonLoader/backups/`.

The ready-to-use `SpoonLoader.jar` is included in this folder. The Gradle files
and Java source are also included so the updater can be rebuilt and audited.
