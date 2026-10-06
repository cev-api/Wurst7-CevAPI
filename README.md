# Wurst Client v7.56 (MC26.3) - Modified by CevAPI
![CevAPI Logo](https://i.imgur.com/WS95aOA.png)

- Original Repo: https://github.com/Wurst-Imperium/Wurst7  
- Installation guide: [https://www.wurstclient.net/tutorials/how-to-install/](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https%3A%2F%2Fwww.wurstclient.net%2Ftutorials%2Fhow-to-install%2F%3Futm_source%3DGitHub%26utm_medium%3DWurst7%2Brepo)  
- Original Wurst Wiki: [https://wurst.wiki/](https://go.wimods.net/from/github.com/Wurst-Imperium/Wurst7?to=https%3A%2F%2Fwurst.wiki%2F%3Futm_source%3DGitHub%26utm_medium%3DWurst7%2Brepo)  
- Wurst 7 CevAPI Wiki: https://github.com/cev-api/Wurst7-CevAPI/wiki
- CevAPI Discord: https://discord.gg/5fddQNST84

## Download
Pre-compiled versioned and polished releases are available on the [Release Page](https://github.com/cev-api/Wurst7-CevAPI/releases). 

[![](https://i.imgur.com/YVRiTjH.png)](https://github.com/cev-api/Wurst7-CevAPI/releases)

I have versions for [1.21.1](https://github.com/cev-api/Wurst7-CevAPI/tree/1.21.1), [1.21.8](https://github.com/cev-api/Wurst7-CevAPI/tree/1.21.8), [1.21.10](https://github.com/cev-api/Wurst7-CevAPI/tree/1.21.10), [1.21.11](https://github.com/cev-api/Wurst7-CevAPI/tree/1.21.11), [26.1.2](https://github.com/cev-api/Wurst7-CevAPI/tree/26.1.2), [26.2](https://github.com/cev-api/Wurst7-CevAPI/tree/26.2) but I only update and maintain the latest; [26.3](https://github.com/cev-api/Wurst7-CevAPI/tree/master)

Not happy with the supported versions? Download [ViaFabricPlus](https://modrinth.com/mod/viafabricplus) and use latest release on an older server.

### Note On Updates

I make changes very often but I publish releases sparingly, so if you want the latest patches/bug fixes and features you **must** compile it yourself. 

![Release](https://i.imgur.com/tq9mfbd.png)

### Compiling Yourself (Pre-Releases)
Don't want to wait for a proper public release? **Grab a compiled copy from the Actions tab in my GitHub page!**

The benefit of this is that it is the exact code here on this repo, no need to download it and setup your own environment to compile, Github has done it for you!

- Sign into Github
- Click the **Actions** tab
- Click latest build at the top with a green tick
- In the **Artifacts** section click **jar** to download

![Step1](https://i.imgur.com/In0c8MI.png)
![Step2](https://i.imgur.com/REX2bSz.png)

**Caveats:**
- May be buggy
- May have unlabelled or hidden features
- Features added may be removed/changed
- Will likely not say its a new version
- Will only support MC26.3
- No NiceWurst

## Custom Builds

Too many hacks? Well you can now make your own custom build of Wurst7-CevAPI!

Run `scripts/custom_build_gui.bat` (or `python scripts/custom_build_gui.py`) to select included hacks, commands, features, and Wurst Options settings, and import custom icons, shaders, or defaults.

Custom builds use `Wurst7-CevAPI-<suffix>` branding, while the Fabric ID and `.minecraft/wurst` config folder remain fixed. Disable the profile toggle for a normal build; enabled profiles are saved to `custom-build/profile.json`.

ClickGUI and AltGUI hide empty categories. Shared infrastructure remains compiled because of Wurst’s mixin dependencies.

## Wurst7-CevAPI In-Game Screenshot
![ModernClickGUI](https://i.imgur.com/a4oNVlf.png)
![Wurst7Cevapi](https://i.imgur.com/4JgZBb8.png)
![AltGUI](https://i.imgur.com/bPEhtxN.png)
![XPGUI](https://i.imgur.com/d5iDhwj.png)
![Keybinds](https://i.imgur.com/JK1IsOV.png)

## Hacks List (316 Hacks)

| Blocks | Movement | Combat | Render | Intel | Tools | Chat | Fun | Items | Other | Creative/Op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| AirPlace | AirWalk | AimAssist | AntiBlind | BaseFinder | AntiCheatDetect | AntiSpam | CustomTotem | AntiBreak | AirMiner | Airstrike+ |
| AntiCactus | AntiEntityPush | AnchorAura | AntiFov | Breadcrumbs | BeaconExploit | AutoChat | Derp | AntiDrop | AntiAFK | ArmorStandImages |
| AreaNuker | AntiGeyser | AntiBlast | AntiWobble | CaveFinder | BundleDupe | AutoComplete | Flicker | AutoDisenchant | Antisocial | AutoCommand |
| AutoBuild | AntiHunger | AntiKnockback | BarrierESP | CoordLogger | CheatDetector | ChatSpam | FunCreepers | AutoDrop | AutoFish | AutoDisplays |
| AutoClicker | AntiVoid | AntiProjectile | BedESP | LivestreamDetector | CrashChest | ChatTranslator | HeadRoll | AutoEat | AutoLibrarian | AutoNames |
| AutoFarm | AntiWaterPush | ArrowDMG | BlockOverlay | LogoutSpots | EntityCount | ClientChatOverlay | LSD | AutoLoot | AutoReconnect | AutoScoreboard |
| AutoMine | AutoFly | AttributeSwap | CameraDistance | Mapa | ForceOP | CommandSpam | MileyCyrus | AutoSteal | AutoTrader | AutoTexts |
| AutoSign | AutoSprint | AutoArmor | CameraNoClip | MicDetect | GameStats | FancyChat | MobView | AutoSwitch | BedrockStash | AutoTitles |
| AutoSpawnProofer | AutoSwim | AutoLeave | ChestESP | MiningEvidence | HideModMenu | InfiniChat | NecoMode | BookBot | DamageDetect | Boom+ |
| AutoTool | AutoWalk | AutoMace | DamageESP | NewChunks | HideWurst | MassTPA | RainbowUI | ChestSearch | FeedAura | ExplosionAura |
| BedBreakAura | BedrockEscape | AutoPotion | DurabilityHUD | NewerNewChunks | KickForensics | Mention | SkinDerp | EnchantmentHandler | GlobalToggle | ForceOPBook |
| BonemealAura | Blink | AutoRespawn | ElytraInfo | OppStats | NbtSizeCounter | NoPlayerChat | Tired | InventorySorter | LootRunner | ForceOPSign |
| BuildRandom | BoatFly | AutoSoup | Freecam | PlayerSonar | OfflineSettings | PlayerMute |  | ItemGenerator | NBTFilter | ForceTP |
| DuraSwap | BoatPhase | AutoSword | Fullbright | ServerIntel | PacketDelay |  |  | ItemHandler | Panic | HandOfGod |
| Excavator | BunnyHop | AutoTotem | HealthTags | SimulationSonar | PacketRate |  |  | KillPotion | PortalGUI | MultiverseAnnihilator |
| FastBreak | ClutchFall | Backtrack | ItemESP | StaffMonitor | Timer |  |  | LootSearch | PotionSaver | NBTEditor |
| FastFill | CreativeFlight | BowAimbot | LavaWaterESP | Telemetrics | UI-Utils |  |  | LootSorter | Reach | OPplayerTPmodule |
| FastPlace | Dolphin | ClickAura | MobESP | TextureRotator |  |  |  | QuickShulker | RemoteEChest | OPServerKillModule |
| HandNoClip | ElytraBounce | Criticals | MobHealth | Triangulator |  |  |  | Restock | SafeTP | UUIDBan |
| InstaBuild | ElytraDive | CrystalAura | MobOwners | TunnelHoleStairESP |  |  |  | SignFramePT | ShearAura | Voider+ |
| InstantBunker | ElytraFlight | FakeLag | MobSearch |  |  |  |  | SusNoMore | Throw |  |
| Kaboom | ElytraPitch | FightBot | MobSpawnESP |  |  |  |  | TrollPotion | TooManyHax |  |
| Liquids | ElytraWalk | InfiniteReach | NameProtect |  |  |  |  | UseItemSpam | VaultRoll |  |
| MusicAura | EntityControl | Killaura | NameTags |  |  |  |  | XCarry | VillagerRoll |  |
| Nuker | ExtraElytra | KillauraLegit | NoBackground |  |  |  |  |  | WebhookAlert |  |
| NukerLegit | FastLadder | MaceDMG | NoFireOverlay |  |  |  |  |  |  |  |
| ScaffoldWalk | Fish | MultiAura | NoFog |  |  |  |  |  |  |  |
| SilkOnly | Flight | Outreach | NoHurtcam |  |  |  |  |  |  |  |
| SourceFill | Follow | PearlIntercept | NoOverlay |  |  |  |  |  |  |  |
| SpeedNuker | Glide | PearlLauncher | NoPumpkin |  |  |  |  |  |  |  |
| StairMaker | HighJump | Protect | NoShieldOverlay |  |  |  |  |  |  |  |
| SuperInstaMine | InvWalk | ShieldSwing | NoVignette |  |  |  |  |  |  |  |
| TargetPlace | Jesus | SpearAssist | NoWeather |  |  |  |  |  |  |  |
| TemplateTool | Jetpack | TP-Aura | OpenWaterESP |  |  |  |  |  |  |  |
| Tillaura | NoClip | TriggerBot | PearlESP |  |  |  |  |  |  |  |
| TreeBot | NoFall | Untouchable | PlayerESP |  |  |  |  |  |  |  |
| Tunneller | NoLevitation | WindChargeKey | PortalESP |  |  |  |  |  |  |  |
| VeinMiner | NoSlowdown |  | PotESP |  |  |  |  |  |  |  |
|  | NoWeb |  | ProjectileESP |  |  |  |  |  |  |  |
|  | Parkour |  | ProphuntESP |  |  |  |  |  |  |  |
|  | PearlDrop |  | Radar |  |  |  |  |  |  |  |
|  | SafeWalk |  | RedstoneESP |  |  |  |  |  |  |  |
|  | Sneak |  | RemoteView |  |  |  |  |  |  |  |
|  | SnowShoe |  | RenderAdjust |  |  |  |  |  |  |  |
|  | SpeedHack |  | RoofESP |  |  |  |  |  |  |  |
|  | Spider |  | Search |  |  |  |  |  |  |  |
|  | Step |  | SignESP |  |  |  |  |  |  |  |
|  | Teleport |  | SkyBuildESP |  |  |  |  |  |  |  |
|  |  |  | SoundMute |  |  |  |  |  |  |  |
|  |  |  | SpawnerESP |  |  |  |  |  |  |  |
|  |  |  | SpawnRadius |  |  |  |  |  |  |  |
|  |  |  | StasisDetector |  |  |  |  |  |  |  |
|  |  |  | SurfaceXray |  |  |  |  |  |  |  |
|  |  |  | Trajectories |  |  |  |  |  |  |  |
|  |  |  | TrialSpawnerESP |  |  |  |  |  |  |  |
|  |  |  | TridentESP |  |  |  |  |  |  |  |
|  |  |  | TrueSight |  |  |  |  |  |  |  |
|  |  |  | Viewmodel |  |  |  |  |  |  |  |
|  |  |  | WardenESP |  |  |  |  |  |  |  |
|  |  |  | Waypoints |  |  |  |  |  |  |  |
|  |  |  | WorkstationESP |  |  |  |  |  |  |  |
|  |  |  | X-Ray |  |  |  |  |  |  |  |
## Full Feature List / Documentation

The README has been shortened due to the large number of features in this client.  
All features, hacks, and detailed explanations are documented in the wiki:

➡️ https://github.com/cev-api/Wurst7-CevAPI/wiki

Note: Wiki is seldom maintained, it is likely out of date. Read release notes in situ. 

### Sections

- [NiceWurst Variant (Cheat-Free Build)](../../wiki/NiceWurst-Variant-Cheat-Free-Build)
- [Novelty](../../wiki/Novelty)
- [Unoriginal](../../wiki/Unoriginal)
- [Wurst Addon API](../../wiki/Wurst-Addon-API)
- [What's new in this fork?](../../wiki/Whats-new-in-this-fork)
- [What's changed or improved in this fork?](../../wiki/Whats-changed-or-improved-in-this-fork)

## Relationship to upstream

This project is a friendly, independent fork of Wurst 7. I originally proposed some features upstream and the maintainers kindly declined, so I decided on a separate fork. I'll continue to maintain my additions and constantly re-base/sync with the upstream project. 

- Upstream repository: https://github.com/Wurst-Imperium/Wurst7  
- This fork: https://github.com/cev-api/Wurst7-CevAPI  
- Status: actively maintaining only the latest release and re-basing as upstream evolves  

All credit for the original client goes to Wurst-Imperium and its contributors. This fork is not affiliated with or endorsed by Wurst-Imperium. This fork maintains the original GPLv3 licensing.

## Security Note

Don't trust binaries? Don't run binaries. Clone the repo, review the diffs, and build it yourself.

Just be consistent: if you're not auditing this, you probably aren't auditing the client/plugins you already run.

## License

This code is licensed under the GNU General Public License v3. 
