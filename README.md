# SMP 1.19.4 v1.26

Paper 1.19.4 / Java 17. Existing SMP v1.25 rules retained.

## Changes
- Sharpness X netherite sword: +1024 attack speed attribute modifier while held; removes modifier when unequipped.
- Protection X helmet: +5 max HP on its own.
- Protection X chestplate: +20 max HP on its own.
- Protection X leggings: +5 max HP on its own.
- Protection X boots: Speed I on their own.
- All four combined: +30 max HP (50 HP total, 25 hearts) and Speed I.
- Corrected EnchantItemEvent import for 1.19.4.

## Build
Upload these files to the root of your GitHub repository and run GitHub Actions > Build SMP Plugin > Run workflow. Download the artifact ZIP and extract `SMP-1.19.4.jar`; upload only that JAR to AMP `/plugins` after backing up `plugins/SMP/state.yml` and removing old `SMP-1.19.4.jar`. Restart the server, verify console says SMP v1.26.0.

Note: Source is updated, but this environment has not compiled or runtime-tested the plugin. Verify the GitHub build succeeds and test in-game.
