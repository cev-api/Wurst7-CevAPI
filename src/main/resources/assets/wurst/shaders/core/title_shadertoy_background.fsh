#version 330

in vec2 texCoord;
in vec4 vertexColor;
out vec4 fragColor;

uniform sampler2D Sampler0;

const float TITLE_ASPECT = 16.0 / 9.0;
const vec2 BLOCK_ATLAS_TILES = vec2(4.0, 3.0);

float titleTime()
{
	vec2 packed = floor(vertexColor.rg * 255.0 + 0.5);
	return (packed.x * 256.0 + packed.y) / 20.0;
}

float hash13(vec3 p3)
{
	p3 = fract(p3 * 0.1031);
	p3 += dot(p3, p3.yzx + 31.32);
	return fract((p3.x + p3.y) * p3.z);
}

float noise(in vec3 x)
{
	vec3 i = floor(x);
	vec3 f = fract(x);
	f = f * f * (3.0 - 2.0 * f);
	float n000 = hash13(i + vec3(0.0, 0.0, 0.0));
	float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
	float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
	float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
	float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
	float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
	float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
	float n111 = hash13(i + vec3(1.0, 1.0, 1.0));
	float nx00 = mix(n000, n100, f.x);
	float nx10 = mix(n010, n110, f.x);
	float nx01 = mix(n001, n101, f.x);
	float nx11 = mix(n011, n111, f.x);
	float nxy0 = mix(nx00, nx10, f.y);
	float nxy1 = mix(nx01, nx11, f.y);
	return mix(nxy0, nxy1, f.z);
}

float mapTerrain(in vec3 p)
{
	p *= 0.1;
	p.xz *= 0.6;

	float time = 0.5 + 0.15 * titleTime();
	float ft = fract(time);
	float it = floor(time);
	ft = smoothstep(0.7, 1.0, ft);
	time = it + ft;
	float spe = 1.4;

	float f = 0.5000 * noise(p * 1.00 + vec3(0.0, 1.0, 0.0) * spe * time);
	f += 0.2500 * noise(p * 2.02 + vec3(0.0, 2.0, 0.0) * spe * time);
	f += 0.1250 * noise(p * 4.01);
	return 25.0 * f - 10.0;
}

vec3 gro = vec3(0.0);

float map(in vec3 c)
{
	vec3 p = c + 0.5;
	float f = mapTerrain(p) + 0.25 * p.y;
	f = mix(f, 1.0, step(length(gro - p), 5.0));
	return step(f, 0.5);
}

float hash31(vec3 p3)
{
	p3 = fract(p3 * 0.1031);
	p3 += dot(p3, p3.yzx + 19.19);
	return fract((p3.x + p3.y) * p3.z);
}

vec3 sampleBlockAtlas(float tile, vec2 uv)
{
	vec2 cell = vec2(mod(tile, BLOCK_ATLAS_TILES.x),
		floor(tile / BLOCK_ATLAS_TILES.x));
	vec2 pixelUv = (floor(fract(uv) * 16.0) + 0.5) / 16.0;
	return texture(Sampler0, (cell + pixelUv) / BLOCK_ATLAS_TILES).rgb;
}

float oreTile(in vec3 vos)
{
	float vein = hash31(floor(vos * 0.55) + vec3(91.7, 17.1, 43.2));
	if(vein < 0.955)
		return -1.0;

	float kind = hash31(floor(vos * 0.23) + vec3(3.9, 77.4, 12.6));
	if(kind < 0.22)
		return 5.0; // coal
	if(kind < 0.40)
		return 6.0; // iron
	if(kind < 0.56)
		return 8.0; // copper
	if(kind < 0.70)
		return 7.0; // gold
	if(kind < 0.83)
		return 11.0; // redstone
	if(kind < 0.93)
		return 9.0; // diamond
	return 10.0; // emerald
}

float blockTile(in vec3 vos, in vec3 nor)
{
	bool airAbove = map(vos + vec3(0.0, 1.0, 0.0)) < 0.5;
	bool nearSurface = map(vos + vec3(0.0, 2.0, 0.0)) < 0.5
		|| map(vos + vec3(0.0, 3.0, 0.0)) < 0.5;

	if(airAbove)
	{
		if(nor.y > 0.5)
			return 0.0; // grass top
		if(nor.y < -0.5)
			return 2.0; // dirt
		return 1.0; // grass side
	}

	if(nearSurface)
		return 2.0; // dirt layer below grass

	float ore = oreTile(vos);
	if(ore >= 0.0)
		return ore;

	float deep = hash31(floor(vos) + vec3(8.8, 1.4, 6.2));
	return vos.y + deep * 8.0 < 8.0 ? 4.0 : 3.0; // deepslate / stone
}

vec3 blockTexture(in vec3 vos, in vec3 nor, in vec3 uvw)
{
	vec2 uv;
	if(abs(nor.y) > 0.5)
		uv = uvw.xz;
	else if(abs(nor.x) > 0.5)
		uv = uvw.zy;
	else
		uv = uvw.xy;

	float tile = blockTile(vos, nor);
	if(abs(nor.y) <= 0.5)
		uv.y = 1.0 - uv.y;

	return sampleBlockAtlas(tile, uv);
}

const vec3 lig = normalize(vec3(-0.4, 0.3, 0.7));

float raycast(in vec3 ro, in vec3 rd, out vec3 oVos, out vec3 oDir)
{
	vec3 pos = floor(ro);
	vec3 ri = 1.0 / rd;
	vec3 rs = sign(rd);
	vec3 dis = (pos - ro + 0.5 + rs * 0.5) * ri;
	float res = -1.0;
	vec3 mm = vec3(0.0);

	for(int i = 0; i < 128; i++)
	{
		if(map(pos) > 0.5)
		{
			res = 1.0;
			break;
		}

		mm = step(dis.xyz, dis.yzx) * step(dis.xyz, dis.zxy);
		dis += mm * rs * ri;
		pos += mm * rs;
	}

	vec3 vos = pos;
	vec3 mini = (pos - ro + 0.5 - 0.5 * vec3(rs)) * ri;
	float t = max(mini.x, max(mini.y, mini.z));

	oDir = mm;
	oVos = vos;
	return t * res;
}

vec3 path(float t, float ya)
{
	vec2 p = 100.0 * sin(0.02 * t * vec2(1.0, 1.2) + vec2(0.1, 0.9));
	p += 50.0 * sin(0.04 * t * vec2(1.3, 1.0) + vec2(1.0, 4.5));
	return vec3(p.x, 18.0 + ya * 4.0 * sin(0.05 * t), p.y);
}

mat3 setCamera(in vec3 ro, in vec3 ta, float cr)
{
	vec3 cw = normalize(ta - ro);
	vec3 cp = vec3(sin(cr), cos(cr), 0.0);
	vec3 cu = normalize(cross(cw, cp));
	vec3 cv = normalize(cross(cu, cw));
	return mat3(cu, cv, -cw);
}

float maxcomp(in vec4 v)
{
	return max(max(v.x, v.y), max(v.z, v.w));
}

float isEdge(in vec2 uv, vec4 va, vec4 vb, vec4 vc, vec4 vd)
{
	vec2 st = 1.0 - uv;
	vec4 wb = smoothstep(0.85, 0.99, vec4(uv.x, st.x, uv.y, st.y))
		* (1.0 - va + va * vc);
	vec4 wc = smoothstep(0.85, 0.99,
		vec4(uv.x * uv.y, st.x * uv.y, st.x * st.y, uv.x * st.y))
		* (1.0 - vb + vd * vb);
	return maxcomp(max(wb, wc));
}

vec3 render(in vec3 ro, in vec3 rd)
{
	vec3 fogCol = vec3(0.16, 0.28, 0.55);
	vec3 col;

	vec3 vos;
	vec3 dir;
	float t = raycast(ro, rd, vos, dir);
	if(t > 0.0)
	{
		vec3 nor = -dir * sign(rd);
		vec3 pos = ro + rd * t;
		vec3 uvw = pos - vos;

		vec3 v1 = vos + nor + dir.yzx;
		vec3 v2 = vos + nor - dir.yzx;
		vec3 v3 = vos + nor + dir.zxy;
		vec3 v4 = vos + nor - dir.zxy;
		vec3 v5 = vos + nor + dir.yzx + dir.zxy;
		vec3 v6 = vos + nor - dir.yzx + dir.zxy;
		vec3 v7 = vos + nor - dir.yzx - dir.zxy;
		vec3 v8 = vos + nor + dir.yzx - dir.zxy;
		vec3 v9 = vos + dir.yzx;
		vec3 v10 = vos - dir.yzx;
		vec3 v11 = vos + dir.zxy;
		vec3 v12 = vos - dir.zxy;
		vec3 v13 = vos + dir.yzx + dir.zxy;
		vec3 v14 = vos - dir.yzx + dir.zxy;
		vec3 v15 = vos - dir.yzx - dir.zxy;
		vec3 v16 = vos + dir.yzx - dir.zxy;

		vec4 vc = vec4(map(v1), map(v2), map(v3), map(v4));
		vec4 vd = vec4(map(v5), map(v6), map(v7), map(v8));
		vec4 va = vec4(map(v9), map(v10), map(v11), map(v12));
		vec4 vb = vec4(map(v13), map(v14), map(v15), map(v16));

		vec2 uv = vec2(dot(dir.yzx, uvw), dot(dir.zxy, uvw));
		float www = 1.0 - isEdge(uv, va, vb, vc, vd);

		col = blockTexture(vos, nor, uvw);
		col *= 1.0 - 0.25 * www;

		float dif = clamp(dot(nor, lig), 0.0, 1.0);
		float bac = clamp(dot(nor, normalize(lig * vec3(-1.0, 0.0, -1.0))),
			0.0, 1.0);
		float sky = 0.5 + 0.5 * nor.y;
		float amb = clamp(0.75 + pos.y / 25.0, 0.0, 1.0);

		vec2 st = 1.0 - uv;
		vec4 wa = vec4(uv.x, st.x, uv.y, st.y) * vc;
		vec4 wb = vec4(uv.x * uv.y, st.x * uv.y, st.x * st.y, uv.x * st.y) * vd
			* (1.0 - vc.xzyw) * (1.0 - vc.zywx);
		float occ = wa.x + wa.y + wa.z + wa.w + wb.x + wb.y + wb.z + wb.w;
		occ = 1.0 - occ / 8.0;
		occ = occ * occ;
		occ = occ * occ;
		occ *= amb;

		vec3 lin = vec3(0.0);
		lin += 1.45 * dif * vec3(1.00, 0.88, 0.72) * (0.6 + 0.4 * occ);
		lin += 0.24 * bac * vec3(0.26, 0.22, 0.18) * occ;
		lin += 0.58 * sky * vec3(0.35, 0.48, 0.72) * occ;
		lin += 0.08 * occ;

		col = col * lin;
		col = mix(col, fogCol, 1.0 - exp(-0.0035 * t));
	}else
	{
		col = mix(fogCol, vec3(0.05, 0.12, 0.38),
			clamp(rd.y * 1.4, 0.0, 1.0));
		float sun = clamp(dot(rd, lig), 0.0, 1.0);
		col += 0.6 * vec3(1.0, 0.7, 0.35) * pow(sun, 6.0);
		col += 2.0 * vec3(1.0, 0.85, 0.55) * pow(sun, 256.0);
	}

	col *= 0.62;
	col = clamp((col * (2.51 * col + 0.03))
		/ (col * (2.43 * col + 0.59) + 0.14), 0.0, 1.0);
	col = pow(col, vec3(1.0 / 2.2));
	float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
	col = mix(vec3(lum), col, 1.08);
	return clamp((col - 0.5) * 0.92 + 0.5, 0.0, 1.0);
}

void main()
{
	vec2 q = texCoord;
	vec2 p = vec2((2.0 * q.x - 1.0) * TITLE_ASPECT, 2.0 * q.y - 1.0);
	float iTime = titleTime();

	float cr = 0.2 * cos(0.1 * iTime);
	vec3 ro = path(iTime + 0.0, 1.0);
	vec3 ta = path(iTime + 5.0, 1.0) - vec3(0.0, 6.0, 0.0);
	gro = ro;

	mat3 cam = setCamera(ro, ta, cr);

	float r2 = p.x * p.x * 0.32 + p.y * p.y;
	p *= (7.0 - sqrt(37.5 - 11.5 * r2)) / (r2 + 1.0);
	vec3 rd = normalize(cam * vec3(p.xy, -2.5));

	vec3 col = render(ro, rd);
	col *= 0.5 + 0.5 * pow(16.0 * q.x * q.y * (1.0 - q.x) * (1.0 - q.y), 0.1);

	fragColor = vec4(col, 1.0);
}
