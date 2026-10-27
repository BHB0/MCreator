# ModLab

**ModLab** is a personal, modernized fork of [MCreator](https://mcreator.net/), the software used to make Minecraft Java Edition mods, Minecraft Bedrock Edition add-ons and data packs.

ModLab is **not** an official MCreator product and is not affiliated with Pylo or Mojang. MCreator is developed by [Pylo](https://mcreator.net/) and its community contributors, and ModLab is based on their work (GPL-3.0). If you like it, please support the people who built the original: https://mcreator.net/donate

## What is different

- New name, logo and colors
- Modern rounded interface with density and corner style settings
- New home page, tabs that can be reordered and pinned
- Command palette (`Ctrl+K`), searchable preferences, simplified toolbar and menus
- One-click workspace sharing and simple Git synchronization
- No usage statistics, news or website requests: ModLab only contacts GitHub (for updates) and Discord (optional rich presence)
- Updates come from the releases of this repository

## Run from source

1. Install Git, the IntelliJ IDEA IDE and a JDK 21 (used to run Gradle)
2. `git clone --recursive https://github.com/BHB0/ModLab.git`
3. Open the folder in IntelliJ, run the Gradle task `setup > downloadJDKWin64` (or the one for your system), then reload Gradle
4. Run the Gradle task `runMCreator`

The internal Java packages keep the `net.mcreator` name on purpose, so that MCreator plugins and existing workspaces keep working.

## Releases

Versions are named `V1.0.0`, `V1.1.0`, ... and published in the [Releases](https://github.com/BHB0/ModLab/releases) page.

## License

ModLab is distributed under the GNU General Public License v3.0, like MCreator. See `LICENSE.txt`.
