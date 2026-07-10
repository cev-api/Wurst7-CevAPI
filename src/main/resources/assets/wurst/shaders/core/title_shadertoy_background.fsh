#version 330

in vec2 texCoord;
in vec4 vertexColor;
out vec4 fragColor;

uniform sampler2D Sampler0;

const float TITLE_ASPECT = 16.0 / 9.0;
const vec2 BLOCK_ATLAS_TILES = vec2(4.0, 3.0);

float titleTime()
{
	vec4 packed = floor(vertexColor * 255.0 + 0.5);
	return (packed.a * 16.0 + floor(packed.r / 16.0)) / 20.0;
}

float titleMode()
{
	float red = floor(vertexColor.r * 255.0 + 0.5);
	return floor(mod(red / 4.0, 4.0));
}

bool titleXrayEnabled()
{
	float red = floor(vertexColor.r * 255.0 + 0.5);
	return mod(floor(red / 2.0), 2.0) > 0.5;
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
	float mode = titleMode();
	p *= 0.1;

	float time = 0.5 + 0.15 * titleTime();
	float ft = fract(time);
	float it = floor(time);
	ft = smoothstep(0.7, 1.0, ft);
	time = it + ft;
	float spe = 1.4;

	if(mode < 0.5)
	{
		p.xz *= 0.60;
		float f =
			0.5000 * noise(p * 1.00 + vec3(0.0, 1.0, 0.0) * spe * time);
		f += 0.2500 * noise(p * 2.02 + vec3(0.0, 2.0, 0.0) * spe * time);
		f += 0.1250 * noise(p * 4.01);
		return 25.0 * f - 10.0;
	}

	if(mode < 1.5)
	{
		p.xz *= 0.52;
		float f =
			0.5500 * noise(p * 1.10 + vec3(0.0, 1.4, 0.0) * spe * time);
		f += 0.2800 * noise(p * 2.30 + vec3(0.0, 2.7, 0.0) * spe * time);
		f += 0.1600 * noise(p * 4.50);
		f += 0.0800 * noise(p * 7.50);
		return 32.0 * f - 14.0;
	}

	p.xz *= 0.40;
	float f = 0.5800 * noise(p * 0.90 + vec3(0.0, 0.7, 0.0) * spe * time);
	f += 0.2700 * noise(p * 1.90 + vec3(0.0, 1.5, 0.0) * spe * time);
	f += 0.1200 * noise(p * 4.00);
	f -= 0.1900 * noise(p * 0.45 + vec3(9.1, 0.0, 7.3));
	return 24.0 * f - 7.0;
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
		return 5.0;
	if(kind < 0.40)
		return 6.0;
	if(kind < 0.56)
		return 8.0;
	if(kind < 0.70)
		return 7.0;
	if(kind < 0.83)
		return 11.0;
	if(kind < 0.93)
		return 9.0;
	return 10.0;
}

float netherOreTile(in vec3 vos)
{
	float vein = hash31(floor(vos * 0.60) + vec3(11.7, 71.1, 23.2));
	if(vein < 0.94)
		return -1.0;

	float kind = hash31(floor(vos * 0.31) + vec3(4.2, 8.3, 12.5));
	if(kind < 0.28)
		return 5.0;
	if(kind < 0.52)
		return 6.0;
	if(kind < 0.72)
		return 8.0;
	if(kind < 0.88)
		return 11.0;
	return 7.0;
}

float overworldBlockTile(in vec3 vos, in vec3 nor)
{
	bool airAbove = map(vos + vec3(0.0, 1.0, 0.0)) < 0.5;
	bool nearSurface = map(vos + vec3(0.0, 2.0, 0.0)) < 0.5
		|| map(vos + vec3(0.0, 3.0, 0.0)) < 0.5;

	if(airAbove)
	{
		if(nor.y > 0.5)
			return 0.0;
		if(nor.y < -0.5)
			return 2.0;
		return 1.0;
	}

	if(nearSurface)
		return 2.0;

	float ore = oreTile(vos);
	if(ore >= 0.0)
		return ore;

	float deep = hash31(floor(vos) + vec3(8.8, 1.4, 6.2));
	return vos.y + deep * 8.0 < 8.0 ? 4.0 : 3.0;
}

float netherBlockTile(in vec3 vos, in vec3 nor)
{
	bool airAbove = map(vos + vec3(0.0, 1.0, 0.0)) < 0.5;
	bool nearSurface = map(vos + vec3(0.0, 2.0, 0.0)) < 0.5
		|| map(vos + vec3(0.0, 3.0, 0.0)) < 0.5;
	float region = hash31(floor(vos * 0.12) + vec3(3.2, 17.4, 29.1));

	if(airAbove)
	{
		if(region < 0.16)
			return 9.0;
		if(region < 0.28)
			return 10.0;
		if(nor.y > 0.5)
			return 0.0;
		if(nor.y < -0.5)
			return 2.0;
		return 1.0;
	}

	if(nearSurface)
		return region < 0.20 ? 10.0 : 2.0;

	float ore = netherOreTile(vos);
	if(ore >= 0.0)
		return ore;

	float deep = hash31(floor(vos * 0.75) + vec3(6.8, 13.1, 2.7));
	if(deep > 0.74)
		return 4.0;
	if(deep > 0.48)
		return 3.0;
	return 2.0;
}

float endBlockTile(in vec3 vos, in vec3 nor)
{
	bool airAbove = map(vos + vec3(0.0, 1.0, 0.0)) < 0.5;
	bool nearSurface = map(vos + vec3(0.0, 2.0, 0.0)) < 0.5
		|| map(vos + vec3(0.0, 3.0, 0.0)) < 0.5;
	float accent = hash31(floor(vos * 0.18) + vec3(12.3, 8.5, 4.9));

	if(airAbove)
	{
		if(accent > 0.972)
			return 10.0;
		if(accent > 0.945)
			return 8.0;
		if(nor.y > 0.5)
			return 0.0;
		if(nor.y < -0.5)
			return 2.0;
		return 1.0;
	}

	if(nearSurface)
		return 2.0;

	float deep = hash31(floor(vos * 0.70) + vec3(9.6, 5.8, 17.3));
	if(deep > 0.985)
		return 4.0;
	if(deep > 0.93)
		return 3.0;
	if(deep > 0.90)
		return 9.0;
	return 2.0;
}

float blockTile(in vec3 vos, in vec3 nor)
{
	float mode = titleMode();
	if(mode < 0.5)
		return overworldBlockTile(vos, nor);
	if(mode < 1.5)
		return netherBlockTile(vos, nor);
	return endBlockTile(vos, nor);
}

bool isXrayTargetBlock(in vec3 vos)
{
	float mode = titleMode();
	if(mode < 0.5)
	{
		float ore = oreTile(vos);
		return ore == 7.0 || ore == 9.0 || ore == 10.0 || ore == 11.0;
	}
	if(mode < 1.5)
	{
		float ore = netherOreTile(vos);
		return ore == 5.0 || ore == 6.0 || ore == 7.0;
	}

	bool airAbove = map(vos + vec3(0.0, 1.0, 0.0)) < 0.5;
	bool nearSurface = map(vos + vec3(0.0, 2.0, 0.0)) < 0.5
		|| map(vos + vec3(0.0, 3.0, 0.0)) < 0.5;
	float accent = hash31(floor(vos * 0.18) + vec3(12.3, 8.5, 4.9));
	if(airAbove)
		return accent > 0.945 && accent <= 0.972;
	if(nearSurface)
		return false;

	float deep = hash31(floor(vos * 0.70) + vec3(9.6, 5.8, 17.3));
	return deep > 0.90;
}

float xrayTargetTile(in vec3 vos)
{
	float mode = titleMode();
	if(mode < 0.5)
		return oreTile(vos);
	if(mode < 1.5)
		return netherOreTile(vos);

	bool airAbove = map(vos + vec3(0.0, 1.0, 0.0)) < 0.5;
	float accent = hash31(floor(vos * 0.18) + vec3(12.3, 8.5, 4.9));
	if(airAbove)
		return accent > 0.945 && accent <= 0.972 ? 8.0 : -1.0;

	float deep = hash31(floor(vos * 0.70) + vec3(9.6, 5.8, 17.3));
	if(deep > 0.985)
		return 4.0;
	if(deep > 0.93)
		return 3.0;
	if(deep > 0.90)
		return 9.0;
	return -1.0;
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

	float tile = titleXrayEnabled() ? xrayTargetTile(vos) : blockTile(vos, nor);
	if(tile < 0.0)
		tile = blockTile(vos, nor);
	if(abs(nor.y) <= 0.5)
		uv.y = 1.0 - uv.y;

	return sampleBlockAtlas(tile, uv);
}

vec3 lightDir(float mode)
{
	if(mode < 0.5)
		return normalize(vec3(-0.4, 0.3, 0.7));
	if(mode < 1.5)
		return normalize(vec3(-0.2, 0.55, 0.35));
	return normalize(vec3(-0.35, 0.45, 0.55));
}

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

float raycastXray(in vec3 ro, in vec3 rd, out vec3 oVos, out vec3 oDir)
{
	vec3 pos = floor(ro);
	vec3 ri = 1.0 / rd;
	vec3 rs = sign(rd);
	vec3 dis = (pos - ro + 0.5 + rs * 0.5) * ri;
	float res = -1.0;
	vec3 mm = vec3(0.0);

	for(int i = 0; i < 128; i++)
	{
		if(map(pos) > 0.5 && isXrayTargetBlock(pos))
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
	float mode = titleMode();
	vec3 fogCol = mode < 0.5 ? vec3(0.16, 0.28, 0.55)
		: mode < 1.5 ? vec3(0.38, 0.10, 0.05) : vec3(0.23, 0.21, 0.31);
	vec3 lig = lightDir(mode);
	vec3 col;

	vec3 vos;
	vec3 dir;
	float t = titleXrayEnabled() ? raycastXray(ro, rd, vos, dir)
		: raycast(ro, rd, vos, dir);
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
		if(mode < 0.5)
		{
			lin += 1.45 * dif * vec3(1.00, 0.88, 0.72) * (0.6 + 0.4 * occ);
			lin += 0.24 * bac * vec3(0.26, 0.22, 0.18) * occ;
			lin += 0.58 * sky * vec3(0.35, 0.48, 0.72) * occ;
			lin += 0.08 * occ;
			col = col * lin;
			col = mix(col, fogCol, 1.0 - exp(-0.0035 * t));
		}else if(mode < 1.5)
		{
			lin += 1.05 * dif * vec3(1.18, 0.48, 0.20) * (0.65 + 0.35 * occ);
			lin += 0.42 * bac * vec3(0.38, 0.10, 0.06) * occ;
			lin += 0.22 * sky * vec3(0.28, 0.08, 0.05) * occ;
			lin += 0.16 * occ * vec3(0.90, 0.26, 0.11);
			col = col * lin;
			col = mix(col, fogCol, 1.0 - exp(-0.0050 * t));
		}else
		{
			lin += 1.20 * dif * vec3(0.94, 0.92, 0.84) * (0.70 + 0.30 * occ);
			lin += 0.16 * bac * vec3(0.18, 0.16, 0.20) * occ;
			lin += 0.46 * sky * vec3(0.42, 0.38, 0.56) * occ;
			lin += 0.11 * occ * vec3(0.70, 0.66, 0.80);
			col = col * lin;
			col = mix(col, fogCol, 1.0 - exp(-0.0042 * t));
		}
	}else
	{
		if(mode < 0.5)
		{
			col = mix(fogCol, vec3(0.05, 0.12, 0.38),
				clamp(rd.y * 1.4, 0.0, 1.0));
			float sun = clamp(dot(rd, lig), 0.0, 1.0);
			col += 0.6 * vec3(1.0, 0.7, 0.35) * pow(sun, 6.0);
			col += 2.0 * vec3(1.0, 0.85, 0.55) * pow(sun, 256.0);
		}else if(mode < 1.5)
		{
			col = mix(vec3(0.09, 0.02, 0.01), fogCol,
				clamp(rd.y * 0.85 + 0.55, 0.0, 1.0));
			float ember = clamp(dot(rd, lig), 0.0, 1.0);
			col += 0.9 * vec3(1.0, 0.32, 0.10) * pow(ember, 9.0);
			col += 0.4 * vec3(0.95, 0.56, 0.14) * pow(ember, 36.0);
		}else
		{
			col = mix(vec3(0.03, 0.02, 0.05), fogCol,
				clamp(rd.y * 1.0 + 0.55, 0.0, 1.0));
			float glow = clamp(dot(rd, lig), 0.0, 1.0);
			col += 0.18 * vec3(0.90, 0.82, 0.96) * pow(glow, 18.0);
		}
	}

	col *= mode < 0.5 ? 0.62 : mode < 1.5 ? 0.68 : 0.60;
	col = clamp((col * (2.51 * col + 0.03))
		/ (col * (2.43 * col + 0.59) + 0.14), 0.0, 1.0);
	col = pow(col, vec3(1.0 / 2.2));
	float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
	col = mix(vec3(lum), col, mode < 1.5 ? 1.08 : 1.03);
	float contrast = mode < 0.5 ? 0.92 : mode < 1.5 ? 0.96 : 0.89;
	return clamp((col - 0.5) * contrast + 0.5, 0.0, 1.0);
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
