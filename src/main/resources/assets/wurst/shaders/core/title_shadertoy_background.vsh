#version 330

in vec3 Position;
in vec4 Color;

out vec2 texCoord;
out vec4 vertexColor;

void main()
{
	gl_Position = vec4(Position.xy, 0.0, 1.0);
	texCoord = Position.xy * 0.5 + 0.5;
	vertexColor = Color;
}
