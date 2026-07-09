param(
	[string]$MinecraftVersion = "",
	[string]$MinecraftJar = "",
	[ValidateSet("overworld", "nether", "end")]
	[string]$Theme = "overworld",
	[string]$Output = "",
	[string]$Metadata = ""
)

$ErrorActionPreference = "Stop"

function Get-ProjectMinecraftVersion {
	$propertiesPath = Join-Path (Get-Location) "gradle.properties"
	if(!(Test-Path $propertiesPath)) {
		return ""
	}
	
	foreach($line in Get-Content $propertiesPath) {
		if($line -match "^\s*minecraft_version\s*=\s*(.+?)\s*$") {
			return $matches[1]
		}
	}
	
	return ""
}

function Find-MinecraftClientJar {
	param([string]$Version)
	
	$loomCache = Join-Path $env:USERPROFILE ".gradle/caches/fabric-loom"
	if(!(Test-Path $loomCache)) {
		throw "Fabric Loom cache not found at $loomCache"
	}
	
	if($Version -ne "") {
		$candidate = Join-Path $loomCache "$Version/minecraft-client.jar"
		if(Test-Path $candidate) {
			return $candidate
		}
	}
	
	$candidates = Get-ChildItem -Path $loomCache -Recurse `
		-Filter "minecraft-client.jar" | Sort-Object LastWriteTime -Descending
	if($candidates.Count -eq 0) {
		throw "Could not find minecraft-client.jar in $loomCache"
	}
	
	return $candidates[0].FullName
}

if($MinecraftJar -eq "") {
	if($MinecraftVersion -eq "") {
		$MinecraftVersion = Get-ProjectMinecraftVersion
	}
	$MinecraftJar = Find-MinecraftClientJar $MinecraftVersion
}

if(!(Test-Path $MinecraftJar)) {
	throw "Minecraft jar not found: $MinecraftJar"
}

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Convert-HexColor {
	param([string]$Hex)
	
	$value = $Hex.TrimStart("#")
	if($value.Length -ne 6) {
		throw "Expected 6-digit RGB color, got: $Hex"
	}
	
	return [System.Drawing.Color]::FromArgb(
		[Convert]::ToInt32($value.Substring(0, 2), 16),
		[Convert]::ToInt32($value.Substring(2, 2), 16),
		[Convert]::ToInt32($value.Substring(4, 2), 16))
}

function Apply-Tint {
	param(
		[System.Drawing.Bitmap]$Bitmap,
		[System.Drawing.Color]$Tint
	)
	
	for($y = 0; $y -lt $Bitmap.Height; $y++) {
		for($x = 0; $x -lt $Bitmap.Width; $x++) {
			$pixel = $Bitmap.GetPixel($x, $y)
			if($pixel.A -eq 0) {
				continue
			}
			
			$Bitmap.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(
				$pixel.A,
				[Math]::Min(255, [Math]::Round($pixel.R * $Tint.R / 255)),
				[Math]::Min(255, [Math]::Round($pixel.G * $Tint.G / 255)),
				[Math]::Min(255, [Math]::Round($pixel.B * $Tint.B / 255))))
		}
	}
}

function Open-BitmapFromZip {
	param(
		[System.IO.Compression.ZipArchive]$Zip,
		[string]$Path
	)
	
	$entry = $Zip.GetEntry($Path)
	if($null -eq $entry) {
		throw "Missing texture in jar: $Path"
	}
	
	$stream = $entry.Open()
	try {
		$image = [System.Drawing.Bitmap]::FromStream($stream)
		try {
			return New-Object System.Drawing.Bitmap($image)
		} finally {
			$image.Dispose()
		}
	} finally {
		$stream.Dispose()
	}
}

function Normalize-BitmapFrame {
	param([System.Drawing.Bitmap]$Bitmap)
	
	if($Bitmap.Height -le $Bitmap.Width -or ($Bitmap.Height % $Bitmap.Width) -ne 0) {
		return $Bitmap
	}
	
	$frame = New-Object System.Drawing.Bitmap($Bitmap.Width, $Bitmap.Width,
		[System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
	$graphics = [System.Drawing.Graphics]::FromImage($frame)
	try {
		$graphics.InterpolationMode =
			[System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
		$graphics.PixelOffsetMode =
			[System.Drawing.Drawing2D.PixelOffsetMode]::Half
		$graphics.DrawImage($Bitmap,
			[System.Drawing.Rectangle]::new(0, 0, $Bitmap.Width, $Bitmap.Width),
			[System.Drawing.Rectangle]::new(0, 0, $Bitmap.Width, $Bitmap.Width),
			[System.Drawing.GraphicsUnit]::Pixel)
	} finally {
		$graphics.Dispose()
		$Bitmap.Dispose()
	}
	
	return $frame
}

function Get-ThemeSprites {
	param([string]$Name)
	
	switch($Name) {
		"overworld" {
			return @(
				@{
					name = "grass_top"
					path = "assets/minecraft/textures/block/grass_block_top.png"
					tint = "#7CBD6B"
				},
				@{
					name = "grass_side"
					path = "assets/minecraft/textures/block/grass_block_side.png"
					overlay = "assets/minecraft/textures/block/grass_block_side_overlay.png"
					overlayTint = "#7CBD6B"
				},
				@{ name = "dirt"; path = "assets/minecraft/textures/block/dirt.png" },
				@{ name = "stone"; path = "assets/minecraft/textures/block/stone.png" },
				@{ name = "deepslate"; path = "assets/minecraft/textures/block/deepslate.png" },
				@{ name = "coal_ore"; path = "assets/minecraft/textures/block/coal_ore.png" },
				@{ name = "iron_ore"; path = "assets/minecraft/textures/block/iron_ore.png" },
				@{ name = "gold_ore"; path = "assets/minecraft/textures/block/gold_ore.png" },
				@{ name = "copper_ore"; path = "assets/minecraft/textures/block/copper_ore.png" },
				@{ name = "diamond_ore"; path = "assets/minecraft/textures/block/diamond_ore.png" },
				@{ name = "emerald_ore"; path = "assets/minecraft/textures/block/emerald_ore.png" },
				@{ name = "redstone_ore"; path = "assets/minecraft/textures/block/redstone_ore.png" }
			)
		}
		"nether" {
			return @(
				@{ name = "crimson_nylium_top"; path = "assets/minecraft/textures/block/crimson_nylium.png" },
				@{ name = "crimson_nylium_side"; path = "assets/minecraft/textures/block/crimson_nylium_side.png" },
				@{ name = "netherrack"; path = "assets/minecraft/textures/block/netherrack.png" },
				@{ name = "blackstone"; path = "assets/minecraft/textures/block/blackstone.png" },
				@{ name = "basalt_side"; path = "assets/minecraft/textures/block/basalt_side.png" },
				@{ name = "nether_quartz_ore"; path = "assets/minecraft/textures/block/nether_quartz_ore.png" },
				@{ name = "nether_gold_ore"; path = "assets/minecraft/textures/block/nether_gold_ore.png" },
				@{ name = "ancient_debris"; path = "assets/minecraft/textures/block/ancient_debris_side.png" },
				@{ name = "magma"; path = "assets/minecraft/textures/block/magma.png" },
				@{ name = "soul_sand"; path = "assets/minecraft/textures/block/soul_sand.png" },
				@{ name = "soul_soil"; path = "assets/minecraft/textures/block/soul_soil.png" },
				@{ name = "glowstone"; path = "assets/minecraft/textures/block/glowstone.png" }
			)
		}
		"end" {
			return @(
				@{ name = "end_stone_top"; path = "assets/minecraft/textures/block/end_stone.png" },
				@{ name = "end_stone_side"; path = "assets/minecraft/textures/block/end_stone.png" },
				@{ name = "end_stone"; path = "assets/minecraft/textures/block/end_stone.png" },
				@{ name = "purpur_pillar_top"; path = "assets/minecraft/textures/block/purpur_pillar_top.png" },
				@{ name = "purpur_block_alt"; path = "assets/minecraft/textures/block/purpur_block.png" },
				@{ name = "chorus_plant"; path = "assets/minecraft/textures/block/chorus_plant.png" },
				@{ name = "chorus_flower"; path = "assets/minecraft/textures/block/chorus_flower.png" },
				@{ name = "chorus_flower_dead"; path = "assets/minecraft/textures/block/chorus_flower_dead.png" },
				@{ name = "purpur_block"; path = "assets/minecraft/textures/block/purpur_block.png" },
				@{ name = "purpur_pillar"; path = "assets/minecraft/textures/block/purpur_pillar_side.png" },
				@{ name = "end_stone_bricks"; path = "assets/minecraft/textures/block/end_stone_bricks.png" },
				@{ name = "purpur_pillar_top_alt"; path = "assets/minecraft/textures/block/purpur_pillar_top.png" }
			)
		}
	}
}

if($Output -eq "") {
	$Output = "src/main/resources/assets/wurst/textures/shader_blocks_$Theme.png"
}

if($Metadata -eq "") {
	$Metadata = "src/main/resources/assets/wurst/textures/shader_blocks_$Theme.json"
}

$sprites = Get-ThemeSprites $Theme

$zip = [System.IO.Compression.ZipFile]::OpenRead($MinecraftJar)
$loaded = @()

try {
	foreach($sprite in $sprites) {
		$bitmap = Normalize-BitmapFrame (Open-BitmapFromZip $zip $sprite.path)
		
		if($sprite.tint) {
			Apply-Tint $bitmap (Convert-HexColor $sprite.tint)
		}
		
		if($sprite.overlay) {
			$overlay = Normalize-BitmapFrame (Open-BitmapFromZip $zip $sprite.overlay)
			try {
				if($sprite.overlayTint) {
					Apply-Tint $overlay (Convert-HexColor $sprite.overlayTint)
				}
				
				$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
				try {
					$graphics.InterpolationMode =
						[System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
					$graphics.PixelOffsetMode =
						[System.Drawing.Drawing2D.PixelOffsetMode]::Half
					$graphics.DrawImage($overlay, 0, 0,
						$bitmap.Width, $bitmap.Height)
				} finally {
					$graphics.Dispose()
				}
			} finally {
				$overlay.Dispose()
			}
		}
		
		$loaded += @{
			name = $sprite.name
			path = $sprite.path
			bitmap = $bitmap
		}
	}
} finally {
	$zip.Dispose()
}

$tileSize = ($loaded | ForEach-Object { $_.bitmap.Width; $_.bitmap.Height } |
	Measure-Object -Maximum).Maximum
$columns = 4
$rows = [Math]::Ceiling($loaded.Count / $columns)
$sheetWidth = $columns * $tileSize
$sheetHeight = $rows * $tileSize

$sheet = New-Object System.Drawing.Bitmap($sheetWidth, $sheetHeight,
	[System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($sheet)
$graphics.InterpolationMode =
	[System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$graphics.PixelOffsetMode =
	[System.Drawing.Drawing2D.PixelOffsetMode]::Half
$graphics.Clear([System.Drawing.Color]::Transparent)

$metadataSprites = @()

try {
	for($i = 0; $i -lt $loaded.Count; $i++) {
		$texture = $loaded[$i]
		$x = ($i % $columns) * $tileSize
		$y = [Math]::Floor($i / $columns) * $tileSize
		$graphics.DrawImage($texture.bitmap, $x, $y, $tileSize, $tileSize)
		
		$metadataSprites += [ordered]@{
			name = $texture.name
			index = $i
			x = $x
			y = $y
			width = $tileSize
			height = $tileSize
			source = $texture.path
		}
	}
} finally {
	$graphics.Dispose()
	foreach($texture in $loaded) {
		$texture.bitmap.Dispose()
	}
}

$outputPath = Resolve-Path -Path (Split-Path $Output -Parent) `
	-ErrorAction SilentlyContinue
if($null -eq $outputPath) {
	New-Item -ItemType Directory -Path (Split-Path $Output -Parent) -Force |
		Out-Null
}

$sheet.Save((Join-Path (Get-Location) $Output),
	[System.Drawing.Imaging.ImageFormat]::Png)
$sheet.Dispose()

$metadataObject = [ordered]@{
	sourceJar = $MinecraftJar
	minecraftVersion = $MinecraftVersion
	theme = $Theme
	tileSize = $tileSize
	columns = $columns
	rows = $rows
	width = $sheetWidth
	height = $sheetHeight
	sprites = $metadataSprites
}

$metadataObject | ConvertTo-Json -Depth 5 |
	Set-Content -Path $Metadata -Encoding UTF8

Write-Host "Generated $Output ($sheetWidth x $sheetHeight)"
Write-Host "Generated $Metadata"
