#define S(x, y, z) smoothstep(x, y, z)
#define sat(x) clamp(x, 0., 1.)
#define HIGH_QUALITY
#define RAIN

vec3 ro, rd;

float N(float t)
{
    return fract(sin(t * 10234.324) * 123423.23512);
}

vec3 N31(float p)
{
    vec3 p3 = fract(vec3(p) * vec3(.1031, .11369, .13787));
    p3 += dot(p3, p3.yzx + 19.19);
    return fract(vec3((p3.x + p3.y) * p3.z,
                      (p3.x + p3.z) * p3.y,
                      (p3.y + p3.z) * p3.x));
}

float SawTooth(float t)
{
    return cos(t + cos(t)) + sin(2. * t) * .2 + sin(4. * t) * .02;
}

float DeltaSawTooth(float t)
{
    return 0.4 * cos(2. * t) + 0.08 * cos(4. * t)
        - (1. - sin(t)) * sin(t + cos(t));
}

vec2 GetDrops(vec2 uv, float seed, float m)
{
    float t = iTime + m * 30.;
    vec2 o = vec2(0.);

    uv.y += t * .05;
    uv *= vec2(10., 2.5) * 2.;
    vec2 id = floor(uv);
    vec3 n = N31(id.x + (id.y + seed) * 546.3524);
    vec2 bd = fract(uv);

    bd -= .5;
    bd.y *= 4.;
    bd.x += (n.x - .5) * .6;

    t += n.z * 6.28;
    float slide = SawTooth(t);

    float ts = 1.5;
    vec2 trailPos = vec2(bd.x * ts,
        (fract(bd.y * ts * 2. - t * 2.) - .5) * .5);

    bd.y += slide * 2.;

    #ifdef HIGH_QUALITY
    float dropShape = bd.x * bd.x;
    dropShape *= DeltaSawTooth(t);
    bd.y += dropShape;
    #endif

    float d = length(bd);

    float trailMask = S(-.2, .2, bd.y);
    trailMask *= bd.y;
    float td = length(trailPos * max(.5, trailMask));

    float mainDrop = S(.2, .1, d);
    float dropTrail = S(.1, .02, td);

    dropTrail *= trailMask;
    o = mix(bd * mainDrop, trailPos, dropTrail);

    return o;
}

void CameraSetup(vec2 uv, vec3 pos, vec3 lookat, float zoom, float m)
{
    ro = pos;
    vec3 f = normalize(lookat - ro);
    vec3 r = normalize(cross(vec3(0., 1., 0.), f));
    vec3 u = normalize(cross(f, r));

    vec2 offs = vec2(0.);

    #ifdef RAIN
    vec2 dropUv = uv;

    offs = GetDrops(dropUv, 1., m);
    offs += 0.70 * GetDrops(dropUv * 1.4, 10., m);

    #ifdef HIGH_QUALITY
    offs += 0.35 * GetDrops(dropUv * 2.4, 25., m);
    #endif
    #endif

    vec3 center = ro + f * zoom;
    vec3 i = center + (uv.x - offs.x) * r + (uv.y - offs.y) * u;
    rd = normalize(i - ro);
}

float hash13(vec3 p3)
{
    p3 = fract(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

float hash12(vec2 p)
{
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise2(vec2 p)
{
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    float a = hash12(i);
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));

    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float terrainState(vec2 xz, float state)
{
    vec2 p = xz * 0.045;
    vec2 seed = vec2(state * 19.17, state * 31.73);

    float f = 0.58 * noise2(p + seed);
    f += 0.27 * noise2(p * 2.03 + seed * 1.37 + vec2(8.1, 2.7));
    f += 0.11 * noise2(p * 4.07 + seed * 0.63 + vec2(-4.6, 11.2));

    float broad = noise2(p * 0.43 + seed * 0.19 + vec2(15.0, -7.0));
    f += (broad - 0.5) * 0.32;

    return f;
}

float terrainHeight(vec2 xz)
{
    float morphTime = iTime * 0.055;
    float state = floor(morphTime);
    float blend = fract(morphTime);
    blend = blend * blend * (3.0 - 2.0 * blend);

    float a = terrainState(xz, state);
    float b = terrainState(xz, state + 1.0);
    float f = mix(a, b, blend);

    return floor((f * 30.0 - 6.5) * 0.94) + 2.0;
}

float map(vec3 c)
{
    return c.y <= terrainHeight(c.xz) ? 1.0 : 0.0;
}

vec3 sampleBlockAtlas(float tile, vec2 uv)
{
    const vec2 atlasTiles = vec2(4.0, 3.0);
    vec2 cell = vec2(mod(tile, atlasTiles.x), floor(tile / atlasTiles.x));
    vec2 pixelUv = (floor(fract(uv) * 16.0) + 0.5) / 16.0;
    return texture(iChannel0, (cell + pixelUv) / atlasTiles).rgb;
}

float oreTile(vec3 vos)
{
    float vein = hash13(floor(vos * 0.55) + vec3(91.7, 17.1, 43.2));
    if(vein < 0.955)
        return -1.0;

    float kind = hash13(floor(vos * 0.23) + vec3(3.9, 77.4, 12.6));
    if(wurstTitleMode < 0.5)
    {
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

    if(wurstTitleMode < 1.5)
    {
        if(kind < 0.34)
            return 5.0;
        if(kind < 0.66)
            return 6.0;
        return 7.0;
    }

    if(kind < 0.22)
        return 4.0;
    if(kind < 0.40)
        return 8.0;
    if(kind < 0.56)
        return 9.0;
    if(kind < 0.70)
        return 4.0;
    return -1.0;
}

bool isXrayTargetBlock(vec3 vos)
{
    float ore = oreTile(vos);
    if(wurstTitleMode < 0.5)
        return ore == 7.0 || ore == 9.0 || ore == 10.0 || ore == 11.0;
    if(wurstTitleMode < 1.5)
        return ore == 5.0 || ore == 6.0 || ore == 7.0;
    return ore == 4.0 || ore == 8.0 || ore == 9.0;
}

vec3 blockTexture(vec3 vos, vec3 nor, vec3 uvw)
{
    vec2 uv;
    if(abs(nor.y) > 0.5)
        uv = uvw.xz;
    else if(abs(nor.x) > 0.5)
        uv = uvw.zy;
    else
        uv = uvw.xy;

    vec2 texel = clamp(floor(uv * 16.0), 0.0, 15.0);
    float n = hash13(vec3(texel, 13.7));
    float top = terrainHeight(vos.xz);
    bool surface = vos.y >= top - 0.6;
    float tile;

    if(wurstXray > 0.5)
        tile = oreTile(vos);
    else if(surface)
    {
        if(nor.y > 0.5)
            tile = 0.0;
        else if(nor.y < -0.5)
            tile = 2.0;
        else
        {
            float grassBand = 12.0 - floor(2.0 * hash13(vec3(texel.x, 5.1, 9.3)));
            tile = texel.y >= grassBand ? 1.0 : 2.0;
            uv.y = 1.0 - uv.y;
        }
    }
    else
    {
        float deep = hash13(floor(vos) * 0.71 + 4.3);
        tile = deep < 0.18 ? 2.0 : 3.0;
    }

    return sampleBlockAtlas(tile, uv);
}

float raycast(vec3 rayOrigin, vec3 rayDirection, out vec3 oVos, out vec3 oDir)
{
    vec3 pos = floor(rayOrigin);
    vec3 ri = 1.0 / rayDirection;
    vec3 rs = sign(rayDirection);
    vec3 dis = (pos - rayOrigin + 0.5 + rs * 0.5) * ri;

    float res = -1.0;
    vec3 mm = vec3(0.0);


    for(int i = 0; i < 256; i++)
    {
        if(pos.y > 28.0 && rayDirection.y >= 0.0)
            break;

        if(map(pos) > 0.5
            && (wurstXray < 0.5 || isXrayTargetBlock(pos)))
        {
            res = 1.0;
            break;
        }

        mm = step(dis.xyz, dis.yzx) * step(dis.xyz, dis.zxy);
        dis += mm * rs * ri;
        pos += mm * rs;
    }

    vec3 mini = (pos - rayOrigin + 0.5 - 0.5 * vec3(rs)) * ri;
    float t = max(mini.x, max(mini.y, mini.z));

    oDir = mm;
    oVos = pos;
    return t * res;
}

float squareMoonMask(vec3 rayDirection, vec3 moonDir, float size)
{
    vec3 mr = normalize(cross(vec3(0.0, 1.0, 0.0), moonDir));
    vec3 mu = normalize(cross(moonDir, mr));
    vec2 mp = vec2(dot(rayDirection, mr), dot(rayDirection, mu));
    float d = max(abs(mp.x), abs(mp.y));
    return smoothstep(size, size - 0.004, d) * smoothstep(0.94, 0.995, dot(rayDirection, moonDir));
}

float starField(vec3 rayDirection)
{
    vec2 uv = rayDirection.xz / max(0.15, 1.0 + rayDirection.y);
    vec2 p = uv * 140.0;
    vec2 id = floor(p);
    vec2 gv = fract(p) - 0.5;
    float n = hash12(id);
    float star = smoothstep(0.08, 0.0, length(gv));
    star *= step(0.992, n);
    star *= smoothstep(0.0, 0.12, rayDirection.y);
    return star;
}

float squareCloudMask(vec2 xz)
{
    vec2 p = xz * 0.018;
    vec2 id = floor(p);
    vec2 f = fract(p) - 0.5;
    float occ = 0.0;

    for(int j = -1; j <= 1; j++)
    {
        for(int i = -1; i <= 1; i++)
        {
            vec2 gid = id + vec2(float(i), float(j));
            float n = hash12(gid * 1.13 + 7.1);
            if(n > 0.58)
            {
                vec2 center = (vec2(hash12(gid + 2.1), hash12(gid + 5.7)) - 0.5) * 0.70;
                vec2 size = vec2(0.55 + 0.65 * hash12(gid + 13.7),
                                 0.24 + 0.28 * hash12(gid + 17.3));
                vec2 d = abs(f - vec2(float(i), float(j)) - center) / size;
                float box = 1.0 - smoothstep(0.92, 1.08, max(d.x, d.y));
                occ = max(occ, box);
            }
        }
    }

    return occ;
}

vec3 nightSky(vec3 rayOrigin, vec3 rayDirection)
{
    vec3 moonDir = normalize(vec3(-0.68, 0.32, 0.66));
    vec3 col = mix(vec3(0.025, 0.035, 0.080), vec3(0.090, 0.120, 0.200), clamp(rayDirection.y * 0.7 + 0.38, 0.0, 1.0));

    float moon = squareMoonMask(rayDirection, moonDir, 0.034);
    float moonGlow = pow(max(dot(rayDirection, moonDir), 0.0), 48.0);
    col += vec3(0.78, 0.84, 0.96) * moon * 0.95;
    col += vec3(0.14, 0.18, 0.26) * moonGlow * 0.28;

    float stars = starField(rayDirection);
    col += vec3(0.75, 0.82, 0.96) * stars * 0.65;

    float denom = max(rayDirection.y + 0.22, 0.18);
    vec2 cuv = rayDirection.xz / denom;
    float cm1 = squareCloudMask(cuv * 34.0 + vec2(12.0, -5.0));
    float cm2 = squareCloudMask(cuv * 23.0 + vec2(-18.0, 9.0));
    float cm = max(cm1 * 0.85, cm2 * 0.65);
    cm *= smoothstep(-0.02, 0.18, rayDirection.y);

    vec3 cloudCol = mix(vec3(0.10, 0.12, 0.18), vec3(0.22, 0.24, 0.30), moon * 0.65 + moonGlow * 0.35);
    col = mix(col, cloudCol, cm * 0.85);

    return col;
}

vec3 renderWorld(vec3 rayOrigin, vec3 rayDirection)
{
    const vec3 moonLig = vec3(-0.68, 0.32, 0.66);
    vec3 fogCol = vec3(0.08, 0.10, 0.15);
    vec3 col;

    vec3 vos, dir;
    float t = raycast(rayOrigin, rayDirection, vos, dir);

    if(t > 0.0)
    {
        vec3 nor = -dir * sign(rayDirection);
        vec3 pos = rayOrigin + rayDirection * t;
        vec3 uvw = pos - vos;

        col = blockTexture(vos, nor, uvw);

        float dif = clamp(dot(nor, normalize(moonLig)), 0.0, 1.0);
        float sky = 0.5 + 0.5 * nor.y;
        float back = clamp(dot(nor, normalize(vec3(-moonLig.x, 0.0, -moonLig.z))), 0.0, 1.0);
        float rim = pow(1.0 - max(0.0, dot(nor, -rayDirection)), 2.0);

        vec3 lin = vec3(0.0);
        lin += 0.50 * dif * vec3(0.70, 0.78, 0.92);
        lin += 0.12 * sky * vec3(0.18, 0.24, 0.36);
        lin += 0.05 * back * vec3(0.08, 0.10, 0.14);
        lin += 0.04 * rim * vec3(0.30, 0.34, 0.46);
        lin += 0.05;

        col *= lin;

        float fog = 1.0 - exp(-0.013 * t);
        fog += smoothstep(8.0, -2.0, pos.y) * 0.10;
        fog = clamp(fog, 0.0, 1.0);
        col = mix(col, fogCol, fog);

        float distanceSoft = smoothstep(18.0, 58.0, t);
        float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
        col = mix(col, mix(vec3(lum), fogCol, 0.20), 0.08 * distanceSoft);
    }
    else
    {
        col = nightSky(rayOrigin, rayDirection);
    }

    col *= 1.00;
    col = clamp((col * (2.51 * col + 0.03))
        / (col * (2.43 * col + 0.59) + 0.14), 0.0, 1.0);
    col = pow(col, vec3(1.0 / 2.2));

    float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(vec3(lum), col, 1.02);

    return col;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord)
{
    vec2 uv = fragCoord.xy / iResolution.xy;
    uv -= 0.5;
    uv.x *= iResolution.x / iResolution.y;

    vec3 pos = vec3(-58.0, 18.0, -68.0);
    vec3 lookat = vec3(8.0, 18.0, 12.0);

    pos.y = max(pos.y, terrainHeight(pos.xz) + 3.0);
    lookat.y = pos.y;

    if(wurstCameraMoving > 0.5)
    {
        float flightTime = iTime * 0.12;
        vec3 flightOffset = vec3(sin(flightTime) * 24.0,
            sin(flightTime * 0.7) * 4.0, cos(flightTime) * 24.0);
        pos += flightOffset;
        lookat += flightOffset;
    }

    CameraSetup(uv, pos, lookat, 1.35, 0.0);

    vec3 col = renderWorld(ro, rd);

    vec2 q = fragCoord / iResolution.xy;
    float vign = 16.0 * q.x * q.y * (1.0 - q.x) * (1.0 - q.y);
    col *= 0.76 + 0.24 * pow(vign, 0.16);

    fragColor = vec4(col, 1.0);
}
