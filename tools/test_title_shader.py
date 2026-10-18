"""GPU regression checks. Requires moderngl and Pillow (pip install moderngl Pillow)."""

from pathlib import Path
import struct
import sys

import moderngl
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/wurst"
ctx = moderngl.create_standalone_context(require=330)
program = ctx.program(
    vertex_shader=(ASSETS / "shaders/core/title_shadertoy_background.vsh").read_text(),
    fragment_shader=(ASSETS / "shaders/core/title_shadertoy_background.fsh").read_text(),
)
program["Sampler0"].value = 0
framebuffer = ctx.simple_framebuffer((320, 180), components=4)
framebuffer.use()
ctx.viewport = (0, 0, 320, 180)


def render(ticks, mode, xray=False, moving=False, animation_time=None):
    flags = mode | (4 if xray else 0) | (8 if moving else 0)
    color = ((ticks >> 8) & 255, ticks & 255, flags, 255)
    if animation_time is None:
        animation_time = ticks / 20 if moving else 0
    data = b"".join(
        struct.pack("3f4B", x, y, animation_time, *color)
        for x, y in [(-1, -1), (1, -1), (-1, 1), (1, 1)]
    )
    buffer = ctx.buffer(data)
    vao = ctx.vertex_array(
        program, [(buffer, "3f 4f1", "Position", "Color")], skip_errors=True
    )
    vao.render(moderngl.TRIANGLE_STRIP)
    vao.release()
    buffer.release()
    return framebuffer.read(components=4)


previews = []
for mode, name in enumerate(("overworld", "nether", "end")):
    image = Image.open(ASSETS / f"textures/shader_blocks_{name}.png").convert("RGBA")
    texture = ctx.texture(image.size, 4, image.tobytes())
    try:
        texture.filter = (moderngl.NEAREST, moderngl.NEAREST)
        texture.use(0)
        for xray in (False, True):
            baseline = render(0, mode, xray)
            # Cover low-byte carries, the former 4096-tick wrap, and full wrap.
            for ticks in (1, 15, 16, 255, 256, 4095, 4096, 32768, 65535):
                assert render(ticks, mode, xray) == baseline, (
                    f"{name}, xray={xray}: stationary scene changed at tick {ticks}"
                )
            assert baseline[3::4] == bytes([255]) * (320 * 180), "Non-opaque background"
            assert len(set(zip(baseline[0::4], baseline[1::4], baseline[2::4]))) > 100, (
                f"{name}: missing terrain or texture detail"
            )
            assert render(400, mode, xray, moving=True) != baseline, "Fly toggle has no effect"
            # Keep packed colour time at zero: only the full-precision clock moves.
            previous_frame = render(0, mode, xray, moving=True, animation_time=0)
            for seconds in (1 / 60, 2 / 60, 3 / 60, 0.5, 1.0):
                frame = render(0, mode, xray, moving=True, animation_time=seconds)
                assert frame != previous_frame, f"{name}: frozen startup frame at {seconds}s"
                previous_frame = frame
            paused_frame = render(0, mode, xray, animation_time=1.0)
            assert render(65535, mode, xray, animation_time=1.0) == paused_frame, (
                f"{name}: paused camera moved with packed colour time"
            )
            if not xray:
                normal_pixels = baseline
                preview = Image.frombytes("RGBA", (320, 180), baseline)
                previews.append(preview.transpose(Image.Transpose.FLIP_TOP_BOTTOM))
            else:
                assert baseline != normal_pixels, f"{name}: X-Ray did not change the scene"
        print(f"{name}: stationary frames, time carries, X-Ray, opacity and camera movement passed")
    finally:
        texture.release()

output = ROOT / "build/title-shader-preview.png"
output.parent.mkdir(parents=True, exist_ok=True)
preview = Image.new("RGBA", (960, 180))
for index, image in enumerate(previews):
    preview.paste(image, (index * 320, 0))
preview.save(output)
print(f"GPU: {ctx.info['GL_RENDERER']}; preview: {output}")

# Optional generated shaders exercise the same desktop GLSL compiler as the game.
for argument in sys.argv[1:]:
    source = Path(argument)
    program = ctx.program(
        vertex_shader=(ASSETS / "shaders/core/title_shadertoy_background.vsh").read_text(),
        fragment_shader=source.read_text(),
    )
    image = Image.open(ASSETS / "textures/shader_blocks_overworld.png").convert("RGBA")
    texture = ctx.texture(image.size, 4, image.tobytes())
    texture.filter = (moderngl.NEAREST, moderngl.NEAREST)
    texture.use(0)
    if "Sampler0" in program:
        program["Sampler0"].value = 0
    pixels = render(0, 0, moving=True)
    assert render(1, 0, moving=True) != pixels, f"{source}: animation did not start immediately"
    assert len(set(zip(pixels[0::4], pixels[1::4], pixels[2::4]))) > 100, (
        f"{source}: custom shader produced a blank frame"
    )
    preview = Image.frombytes("RGBA", (320, 180), pixels)
    preview.transpose(Image.Transpose.FLIP_TOP_BOTTOM).save(
        ROOT / "build" / f"{source.stem}-preview.png"
    )
    print(f"{source.name}: compiled and rendered successfully")
    texture.release()
