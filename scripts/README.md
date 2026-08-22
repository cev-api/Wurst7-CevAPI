# Scripts

Run Python scripts from the repository root with Python 3.

| Script | Purpose |
|---|---|
| `custom_build_gui.bat` | Windows launcher for the custom-build GUI. |
| `custom_build_gui.py` | Creates filtered Wurst7-CevAPI builds; selects hacks, commands, features, and Wurst Options settings, and imports optional assets/defaults. |
| `check_hack_translations.py` | Checks every registered hack for missing or empty English names and descriptions. |
| `find_missing_translations.py` | Scans Java source for referenced translation keys missing from `assets/wurst/translations/en_us.json`. |
| `update_version_constants.py` | Updates Minecraft, Fabric Loader, Fabric API, and mod-version values in `gradle.properties`. |
| `premerge.cmd` | Fetches a GitHub pull request into a local branch for testing. It changes the current Git branch. |

## Common commands

```powershell
python scripts/check_hack_translations.py
python scripts/find_missing_translations.py
python scripts/update_version_constants.py --help
python scripts/custom_build_gui.py
```
