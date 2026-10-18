#version 330

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D Sampler0;

// Compiled when no custom source is installed. The built-in pipeline stays separate.
void main()
{
	fragColor = vec4(texture(Sampler0, texCoord).rgb, 1.0);
}
