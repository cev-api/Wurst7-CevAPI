// afl_ext 2017-2024
// MIT License

// Use your mouse to move the camera around! Press the Left Mouse Button on the image to look around!

#define DRAG_MULT 0.38 // changes how much waves pull on the water
#define WATER_DEPTH 1.0 // how deep is the water
#define CAMERA_HEIGHT 1.5 // how high the camera should be
#define ITERATIONS_RAYMARCH 12 // waves iterations of raymarching
#define ITERATIONS_NORMAL 36 // waves iterations when calculating normals

// Volumetric cloud controls
#define CLOUD_BASE_HEIGHT 8.0
#define CLOUD_TOP_HEIGHT 14.0
#define CLOUD_MAX_DISTANCE 140.0
#define CLOUD_STEPS 22
#define CLOUD_LIGHT_STEPS 4
#define CLOUD_SCALE 0.075
#define CLOUD_DETAIL_SCALE 0.210
#define CLOUD_COVERAGE 0.735
#define CLOUD_DENSITY 0.72
#define CLOUD_SPEED 0.025

#define NormalizedMouse (iMouse.xy / iResolution.xy) // normalize mouse coords

// Hash for stars and cloud noise
float hash31(vec3 p) {
  p = fract(p * 0.1031);
  p += dot(p, p.yzx + 33.33);
  return fract((p.x + p.y) * p.z);
}

// Smooth 3D value noise
float noise3d(vec3 p) {
  vec3 i = floor(p);
  vec3 f = fract(p);

  f = f * f * (3.0 - 2.0 * f);

  float n000 = hash31(i + vec3(0.0, 0.0, 0.0));
  float n100 = hash31(i + vec3(1.0, 0.0, 0.0));
  float n010 = hash31(i + vec3(0.0, 1.0, 0.0));
  float n110 = hash31(i + vec3(1.0, 1.0, 0.0));
  float n001 = hash31(i + vec3(0.0, 0.0, 1.0));
  float n101 = hash31(i + vec3(1.0, 0.0, 1.0));
  float n011 = hash31(i + vec3(0.0, 1.0, 1.0));
  float n111 = hash31(i + vec3(1.0, 1.0, 1.0));

  float nx00 = mix(n000, n100, f.x);
  float nx10 = mix(n010, n110, f.x);
  float nx01 = mix(n001, n101, f.x);
  float nx11 = mix(n011, n111, f.x);

  float nxy0 = mix(nx00, nx10, f.y);
  float nxy1 = mix(nx01, nx11, f.y);

  return mix(nxy0, nxy1, f.z);
}

// Layered cloud noise
float fbm3d(vec3 p) {
  float value = 0.0;
  float amplitude = 0.5;

  for(int i = 0; i < 5; i++) {
    value += noise3d(p) * amplitude;
    p = p * 2.03 + vec3(13.7, 7.1, 19.3);
    amplitude *= 0.5;
  }

  return value;
}

// Dense multi-layer star field
vec3 getStars(vec3 dir) {
  vec3 normalizedDir = normalize(dir);
  vec3 stars = vec3(0.0);

  // Large stars
  vec3 starPosition = normalizedDir * 540.0;
  vec3 starCell = floor(starPosition);
  vec3 starLocal = fract(starPosition) - 0.5;

  float randomValue = hash31(starCell);

  float starExists = step(0.9650, randomValue);

  float starDistance = length(starLocal);
  float starCore = smoothstep(0.125, 0.0, starDistance);
  float starGlow = smoothstep(0.320, 0.0, starDistance) * 0.28;

  float twinkle = 0.72
    + 0.28
    * sin(
      iTime * (1.5 + randomValue * 3.0)
      + randomValue * 31.0
    );

  vec3 coldStar = vec3(0.62, 0.72, 1.0);
  vec3 warmStar = vec3(1.0, 0.66, 0.76);

  vec3 starColor = mix(
    coldStar,
    warmStar,
    hash31(starCell + 7.3)
  );

  stars += starColor
    * starExists
    * (starCore * 3.2 + starGlow)
    * twinkle;

  // Medium stars
  vec3 finePosition = normalizedDir * 870.0;
  vec3 fineCell = floor(finePosition);
  vec3 fineLocal = fract(finePosition) - 0.5;

  float fineRandom = hash31(fineCell);

  float fineExists = step(0.9700, fineRandom);
  float fineDistance = length(fineLocal);
  float fineCore = smoothstep(0.095, 0.0, fineDistance);
  float fineGlow = smoothstep(0.210, 0.0, fineDistance) * 0.11;

  vec3 fineColor = mix(
    vec3(0.68, 0.75, 1.0),
    vec3(1.0, 0.74, 0.84),
    hash31(fineCell + 2.1)
  );

  stars += fineColor
    * fineExists
    * (fineCore * 1.45 + fineGlow);

  // Small stars
  vec3 microPosition = normalizedDir * 1250.0;
  vec3 microCell = floor(microPosition);
  vec3 microLocal = fract(microPosition) - 0.5;

  float microRandom = hash31(microCell);
  float microExists = step(0.9750, microRandom);
  float microStar = smoothstep(
    0.074,
    0.0,
    length(microLocal)
  );

  vec3 microColor = mix(
    vec3(0.72, 0.80, 1.0),
    vec3(0.96, 0.78, 1.0),
    hash31(microCell + 5.7)
  );

  stars += microColor
    * microExists
    * microStar
    * 1.05;

  // Tiny stars
  vec3 tinyPosition = normalizedDir * 1750.0;
  vec3 tinyCell = floor(tinyPosition);
  vec3 tinyLocal = fract(tinyPosition) - 0.5;

  float tinyRandom = hash31(tinyCell);
  float tinyExists = step(0.9800, tinyRandom);
  float tinyStar = smoothstep(
    0.060,
    0.0,
    length(tinyLocal)
  );

  vec3 tinyColor = mix(
    vec3(0.68, 0.76, 1.0),
    vec3(1.0, 0.82, 0.90),
    hash31(tinyCell + 10.2)
  );

  stars += tinyColor
    * tinyExists
    * tinyStar
    * 0.78;

  // Background stars
  vec3 dustPosition = normalizedDir * 2400.0;
  vec3 dustCell = floor(dustPosition);
  vec3 dustLocal = fract(dustPosition) - 0.5;

  float dustRandom = hash31(dustCell);
  float dustExists = step(0.9840, dustRandom);
  float dustStar = smoothstep(
    0.052,
    0.0,
    length(dustLocal)
  );

  stars += vec3(0.72, 0.78, 1.0)
    * dustExists
    * dustStar
    * 0.58;

  // Distant stars
  vec3 distantPosition = normalizedDir * 3400.0;
  vec3 distantCell = floor(distantPosition);
  vec3 distantLocal = fract(distantPosition) - 0.5;

  float distantRandom = hash31(distantCell);
  float distantExists = step(0.9880, distantRandom);
  float distantStar = smoothstep(
    0.045,
    0.0,
    length(distantLocal)
  );

  stars += mix(
      vec3(0.62, 0.70, 1.0),
      vec3(0.94, 0.76, 1.0),
      hash31(distantCell + 17.4)
    )
    * distantExists
    * distantStar
    * 0.44;

  // Milky Way band
  vec3 galaxyNormal = normalize(
    vec3(0.18, 0.96, 0.22)
  );

  float galaxyDistance = abs(
    dot(normalizedDir, galaxyNormal)
  );

  float galaxyBand = exp(
    -galaxyDistance
    * galaxyDistance
    * 26.0
  );

  // Stars within the Milky Way band
  vec3 galaxyPosition = normalizedDir * 1550.0;
  vec3 galaxyCell = floor(galaxyPosition);
  vec3 galaxyLocal = fract(galaxyPosition) - 0.5;

  float galaxyRandom = hash31(galaxyCell + 25.0);
  float galaxyExists = step(
    mix(0.9930, 0.9550, galaxyBand),
    galaxyRandom
  );

  float galaxyStar = smoothstep(
    0.070,
    0.0,
    length(galaxyLocal)
  );

  stars += mix(
      vec3(0.62, 0.68, 1.0),
      vec3(0.92, 0.68, 1.0),
      hash31(galaxyCell + 3.4)
    )
    * galaxyExists
    * galaxyStar
    * galaxyBand
    * 0.95;

  // Stellar glow
  stars += vec3(
    0.026,
    0.020,
    0.060
  ) * galaxyBand;

  return stars;
}
// Thin cloud density
float getCloudDensity(vec3 position) {
  vec3 wind = vec3(
    iTime * CLOUD_SPEED,
    0.0,
    iTime * CLOUD_SPEED * 0.32
  );

  vec3 cloudPosition = position;
  cloudPosition.xz += wind.xz;

  float heightFraction = clamp(
    (position.y - CLOUD_BASE_HEIGHT)
      / (CLOUD_TOP_HEIGHT - CLOUD_BASE_HEIGHT),
    0.0,
    1.0
  );

  float lowerFade = smoothstep(0.0, 0.18, heightFraction);
  float upperFade = 1.0 - smoothstep(0.68, 1.0, heightFraction);
  float verticalShape = lowerFade * upperFade;

  // Stretch clouds into sparse streaks
  vec3 basePosition = cloudPosition;
  basePosition.x *= 0.42;
  basePosition.z *= 0.85;
  basePosition.y *= 1.75;

  vec3 detailPosition = cloudPosition;
  detailPosition.x *= 0.58;
  detailPosition.z *= 1.10;
  detailPosition.y *= 2.20;

  float baseNoise = fbm3d(basePosition * CLOUD_SCALE);
  float detailNoise = fbm3d(detailPosition * CLOUD_DETAIL_SCALE);

  float density = baseNoise
    + detailNoise * 0.22
    - CLOUD_COVERAGE;

  density = max(density, 0.0);
  density *= verticalShape;
  density *= CLOUD_DENSITY;

  return density;
}

// Cloud layer intersection
vec2 intersectCloudLayer(vec3 origin, vec3 direction) {
  if(abs(direction.y) < 0.0001) {
    return vec2(-1.0);
  }

  float bottomHit = (CLOUD_BASE_HEIGHT - origin.y) / direction.y;
  float topHit = (CLOUD_TOP_HEIGHT - origin.y) / direction.y;

  return vec2(
    min(bottomHit, topHit),
    max(bottomHit, topHit)
  );
}

// Moon direction
vec3 getMoonDirection() {
  return normalize(vec3(-0.42, 0.58, -0.70));
}

// Moon and halo
vec3 getMoon(vec3 dir) {
  vec3 moonDirection = getMoonDirection();
  float moonAmount = max(0.0, dot(normalize(dir), moonDirection));

  float moonDisk = smoothstep(0.99950, 0.99982, moonAmount);
  float moonGlow = pow(moonAmount, 90.0) * 0.18;
  float moonHalo = pow(moonAmount, 18.0) * 0.06;

  vec3 moonColor = vec3(0.92, 0.95, 1.0);

  return moonColor * (moonDisk * 2.4 + moonGlow + moonHalo);
}

// Moon reflection highlight
vec3 getMoonReflectionHighlight(vec3 reflectedRay) {
  vec3 moonDirection = getMoonDirection();
  float moonAlignment = max(
    0.0,
    dot(normalize(reflectedRay), moonDirection)
  );

  float tightCore = pow(moonAlignment, 420.0);
  float brightLobe = pow(moonAlignment, 52.0);
  float wideSheen = pow(moonAlignment, 14.0) * 0.035;

  vec3 moonColor = vec3(0.92, 0.95, 1.0);

  return moonColor * (
    tightCore * 5.4
    + brightLobe * 0.85
    + wideSheen
  );
}

// Cloud light absorption
float sampleCloudLight(vec3 position) {
  vec3 lightDirection = getMoonDirection();
  vec3 samplePosition = position;
  float transmittance = 1.0;

  for(int i = 0; i < CLOUD_LIGHT_STEPS; i++) {
    samplePosition += lightDirection * 1.45;

    float density = getCloudDensity(samplePosition);
    transmittance *= exp(-density * 2.1);
  }

  return clamp(transmittance, 0.0, 1.0);
}

// Volumetric cloud integration
vec3 renderVolumetricClouds(
  vec3 origin,
  vec3 direction,
  vec3 background,
  vec2 pixel
) {
  if(direction.y <= 0.005) {
    return background;
  }

  vec2 cloudIntersection = intersectCloudLayer(origin, direction);

  float startDistance = max(cloudIntersection.x, 0.0);
  float endDistance = min(
    cloudIntersection.y,
    CLOUD_MAX_DISTANCE
  );

  if(endDistance <= startDistance) {
    return background;
  }

  float stepSize = (endDistance - startDistance)
    / float(CLOUD_STEPS);

  // Stable screen-space jitter prevents temporal stepping
  float jitter = fract(
    52.9829189 * fract(
      0.06711056 * pixel.x
        + 0.00583715 * pixel.y
    )
  );

  float distanceAlongRay = startDistance
    + stepSize * jitter;

  vec3 accumulatedColor = vec3(0.0);
  float transmittance = 1.0;

  vec3 moonDirection = getMoonDirection();

  for(int i = 0; i < CLOUD_STEPS; i++) {
    if(
      distanceAlongRay > endDistance
      || transmittance < 0.015
    ) {
      break;
    }

    vec3 position = origin
      + direction * distanceAlongRay;

    float density = getCloudDensity(position);

    if(density > 0.0005) {
      float lightAmount = sampleCloudLight(position);

      float forwardScattering = pow(
        max(0.0, dot(direction, moonDirection)),
        5.0
      );

      float sideLighting = 0.24
        + lightAmount * 0.76;

      vec3 cloudShadowColor = vec3(
        0.075,
        0.065,
        0.135
      );

      vec3 cloudLightColor = vec3(
        0.72,
        0.72,
        0.90
      );

      vec3 cloudColor = mix(
        cloudShadowColor,
        cloudLightColor,
        sideLighting
      );

      cloudColor += vec3(0.22, 0.12, 0.30)
        * forwardScattering
        * lightAmount;

      float alpha = 1.0 - exp(
        -density * stepSize * 1.25
      );

      accumulatedColor += transmittance
        * cloudColor
        * alpha;

      transmittance *= 1.0 - alpha;
    }

    distanceAlongRay += stepSize;
  }

  return accumulatedColor
    + background * transmittance;
}

// Calculates wave value and its derivative, 
// for the wave direction, position in space, wave frequency and time
vec2 wavedx(vec2 position, vec2 direction, float frequency, float timeshift) {
  float x = dot(direction, position) * frequency + timeshift;
  float wave = exp(sin(x) - 1.0);
  float dx = wave * cos(x);
  return vec2(wave, -dx);
}

// Calculates waves by summing octaves of various waves with various parameters
float getwaves(vec2 position, int iterations) {
  float wavePhaseShift = length(position) * 0.1; // this is to avoid every octave having exactly the same phase everywhere
  float iter = 0.0; // this will help generating well distributed wave directions
  float frequency = 1.0; // frequency of the wave, this will change every iteration
  float timeMultiplier = 2.0; // time multiplier for the wave, this will change every iteration
  float weight = 1.0;// weight in final sum for the wave, this will change every iteration
  float sumOfValues = 0.0; // will store final sum of values
  float sumOfWeights = 0.0; // will store final sum of weights
  for(int i=0; i < iterations; i++) {
    // generate some wave direction that looks kind of random
    vec2 p = vec2(sin(iter), cos(iter));
    
    // calculate wave data
    vec2 res = wavedx(position, p, frequency, iTime * timeMultiplier + wavePhaseShift);

    // shift position around according to wave drag and derivative of the wave
    position += p * res.y * weight * DRAG_MULT;

    // add the results to sums
    sumOfValues += res.x * weight;
    sumOfWeights += weight;

    // modify next octave ;
    weight = mix(weight, 0.0, 0.2);
    frequency *= 1.18;
    timeMultiplier *= 1.07;

    // add some kind of random value to make next wave look random too
    iter += 1232.399963;
  }
  // calculate and return
  return sumOfValues / sumOfWeights;
}

// Raymarches the ray from top water layer boundary to low water layer boundary
float raymarchwater(vec3 camera, vec3 start, vec3 end, float depth) {
  vec3 pos = start;
  vec3 dir = normalize(end - start);
  for(int i=0; i < 64; i++) {
    // the height is from 0 to -depth
    float height = getwaves(pos.xz, ITERATIONS_RAYMARCH) * depth - depth;
    // if the waves height almost nearly matches the ray height, assume its a hit and return the hit distance
    if(height + 0.01 > pos.y) {
      return distance(pos, camera);
    }
    // iterate forwards according to the height mismatch
    pos += dir * (pos.y - height);
  }
  // if hit was not registered, just assume hit the top layer, 
  // this makes the raymarching faster and looks better at higher distances
  return distance(start, camera);
}

// Calculate normal at point by calculating the height at the pos and 2 additional points very close to pos
vec3 normal(vec2 pos, float e, float depth) {
  vec2 ex = vec2(e, 0);
  float H = getwaves(pos.xy, ITERATIONS_NORMAL) * depth;
  vec3 a = vec3(pos.x, H, pos.y);
  return normalize(
    cross(
      a - vec3(pos.x - e, getwaves(pos.xy - ex.xy, ITERATIONS_NORMAL) * depth, pos.y), 
      a - vec3(pos.x, getwaves(pos.xy + ex.yx, ITERATIONS_NORMAL) * depth, pos.y + e)
    )
  );
}

// Helper function generating a rotation matrix around the axis by the angle
mat3 createRotationMatrixAxisAngle(vec3 axis, float angle) {
  float s = sin(angle);
  float c = cos(angle);
  float oc = 1.0 - c;
  return mat3(
    oc * axis.x * axis.x + c, oc * axis.x * axis.y - axis.z * s, oc * axis.z * axis.x + axis.y * s, 
    oc * axis.x * axis.y + axis.z * s, oc * axis.y * axis.y + c, oc * axis.y * axis.z - axis.x * s, 
    oc * axis.z * axis.x - axis.y * s, oc * axis.y * axis.z + axis.x * s, oc * axis.z * axis.z + c
  );
}

// Helper function that generates camera ray based on UV and mouse
vec3 getRay(vec2 fragCoord) {
  vec2 uv = ((fragCoord.xy / iResolution.xy) * 2.0 - 1.0) * vec2(iResolution.x / iResolution.y, 1.0);
  // for fisheye, uncomment following line and comment the next one
  //vec3 proj = normalize(vec3(uv.x, uv.y, 1.0) + vec3(uv.x, uv.y, -1.0) * pow(length(uv), 2.0) * 0.05);  
  vec3 proj = normalize(vec3(uv.x, uv.y, 1.5));
  if(iResolution.x < 600.0) {
    return proj;
  }
  return createRotationMatrixAxisAngle(vec3(0.0, -1.0, 0.0), 3.0 * ((NormalizedMouse.x + 0.5) * 2.0 - 1.0)) 
    * createRotationMatrixAxisAngle(vec3(1.0, 0.0, 0.0), 0.5 + 1.5 * (((NormalizedMouse.y == 0.0 ? 0.27 : NormalizedMouse.y) * 1.0) * 2.0 - 1.0))
    * proj;
}

// Ray-Plane intersection checker
float intersectPlane(vec3 origin, vec3 direction, vec3 point, vec3 normal) { 
  return clamp(dot(point - origin, normal) / dot(direction, normal), -1.0, 9991999.0); 
}

// Some very barebones but fast atmosphere approximation
vec3 extra_cheap_atmosphere(vec3 raydir, vec3 sundir) {
  //sundir.y = max(sundir.y, -0.07);
  float special_trick = 1.0 / (raydir.y * 1.0 + 0.1);
  float special_trick2 = 1.0 / (sundir.y * 11.0 + 1.0);
  float raysundt = pow(abs(dot(sundir, raydir)), 2.0);
  float sundt = pow(max(0.0, dot(sundir, raydir)), 8.0);
  float mymie = sundt * special_trick * 0.2;
  vec3 suncolor = mix(vec3(1.0), max(vec3(0.0), vec3(1.0) - vec3(5.5, 13.0, 22.4) / 22.4), special_trick2);
  vec3 bluesky= vec3(5.5, 13.0, 22.4) / 22.4 * suncolor;
  vec3 bluesky2 = max(vec3(0.0), bluesky - vec3(5.5, 13.0, 22.4) * 0.002 * (special_trick + -6.0 * sundir.y * sundir.y));
  bluesky2 *= special_trick * (0.24 + raysundt * 0.24);
  return bluesky2 * (1.0 + 1.0 * pow(1.0 - raydir.y, 3.0));
} 

// Calculate where the sun should be, it will be moving around the sky
vec3 getSunDirection() {
  // Keep the sun below the horizon
  return normalize(vec3(-0.0773502691896258, -0.65, 0.5773502691896258));
}

// Get atmosphere color for given direction
vec3 getAtmosphere(vec3 dir) {
  // Night sky palette
  float height = clamp(dir.y, 0.0, 1.0);

  vec3 horizonColor = vec3(
    0.095,
    0.004,
    0.085
  );

  vec3 middleColor = vec3(
    0.014,
    0.008,
    0.065
  );

  vec3 zenithColor = vec3(
    0.0010,
    0.0015,
    0.0120
  );

  vec3 sky = mix(
    horizonColor,
    middleColor,
    smoothstep(0.0, 0.28, height)
  );

  sky = mix(
    sky,
    zenithColor,
    smoothstep(0.20, 1.0, height)
  );

  // Crimson horizon glow
  float horizonGlow = exp(-height * 12.0);

  sky += vec3(
    0.095,
    0.001,
    0.020
  ) * horizonGlow;

  // Celestial haze
  vec3 hazeNormal = normalize(vec3(0.22, 0.92, 0.31));
  float celestialBand = exp(
    -pow(abs(dot(dir, hazeNormal)), 2.0) * 30.0
  );

  sky += vec3(
    0.014,
    0.006,
    0.035
  ) * celestialBand;

  sky += getStars(dir);

  return sky;
}

// Get sun color for given direction
vec3 getSun(vec3 dir) { 
  // Hide the sun
  return vec3(0.0);
}

// Great tonemapping function from my other shader: https://www.shadertoy.com/view/XsGfWV
vec3 aces_tonemap(vec3 color) {  
  mat3 m1 = mat3(
    0.59719, 0.07600, 0.02840,
    0.35458, 0.90834, 0.13383,
    0.04823, 0.01566, 0.83777
  );
  mat3 m2 = mat3(
    1.60475, -0.10208, -0.00327,
    -0.53108,  1.10813, -0.07276,
    -0.07367, -0.00605,  1.07602
  );
  vec3 v = m1 * color;  
  vec3 a = v * (v + 0.0245786) - 0.000090537;
  vec3 b = v * (0.983729 * v + 0.4329510) + 0.238081;
  return pow(clamp(m2 * (a / b), 0.0, 1.0), vec3(1.0 / 2.2));  
}

// Main
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
  // get the ray
  vec3 ray = getRay(fragCoord);

  // define ray origin, moving around
  vec3 origin = vec3(iTime * 0.2, CAMERA_HEIGHT, 1);

  if(ray.y >= 0.0) {
    // if ray.y is positive, render the sky
    vec3 sky = getAtmosphere(ray) + getMoon(ray) + getSun(ray);

    // Volumetric clouds
    vec3 C = renderVolumetricClouds(
      origin,
      ray,
      sky,
      fragCoord
    );

    // Night exposure
    fragColor = vec4(aces_tonemap(C * 1.45),1.0);   
    return;
  }

  // now ray.y must be negative, water must be hit
  // define water planes
  vec3 waterPlaneHigh = vec3(0.0, 0.0, 0.0);
  vec3 waterPlaneLow = vec3(0.0, -WATER_DEPTH, 0.0);

  // calculate intersections and reconstruct positions
  float highPlaneHit = intersectPlane(origin, ray, waterPlaneHigh, vec3(0.0, 1.0, 0.0));
  float lowPlaneHit = intersectPlane(origin, ray, waterPlaneLow, vec3(0.0, 1.0, 0.0));
  vec3 highHitPos = origin + ray * highPlaneHit;
  vec3 lowHitPos = origin + ray * lowPlaneHit;

  // raymatch water and reconstruct the hit pos
  float dist = raymarchwater(origin, highHitPos, lowHitPos, WATER_DEPTH);
  vec3 waterHitPos = origin + ray * dist;

  // calculate normal at the hit position
  vec3 N = normal(waterHitPos.xz, 0.01, WATER_DEPTH);

  // smooth the normal with distance to avoid disturbing high frequency noise
  N = mix(N, vec3(0.0, 1.0, 0.0), 0.8 * min(1.0, sqrt(dist*0.01) * 1.1));

  // calculate fresnel coefficient
  float fresnel = (0.04 + (1.0-0.04)*(pow(1.0 - max(0.0, dot(-N, ray)), 5.0)));

  // reflect the ray and make sure it bounces up
  vec3 R = normalize(reflect(ray, N));
  R.y = abs(R.y);
  
  // calculate the reflection and approximate subsurface scattering
  vec3 reflectionBackground = getAtmosphere(R) + getMoon(R) + getSun(R);

  // Reflected sky and clouds
  vec3 reflection = renderVolumetricClouds(
    waterHitPos + N * 0.05,
    R,
    reflectionBackground,
    fragCoord
  );

  // Moon glint
  reflection += getMoonReflectionHighlight(R);

  // Blood-red water
  float waterHeight = clamp(
    (waterHitPos.y + WATER_DEPTH) / WATER_DEPTH,
    0.0,
    1.0
  );

  vec3 deepWaterColor = vec3(
    0.075,
    0.0002,
    0.0008
  );

  vec3 surfaceWaterColor = vec3(
    0.360,
    0.0010,
    0.0034
  );

  vec3 scatteringColor = mix(
    deepWaterColor,
    surfaceWaterColor,
    waterHeight
  );

  vec3 scattering = scatteringColor
    * (0.30 + waterHeight * 0.72);

  // Distant red glow
  float distanceGlow = 1.0 - exp(-dist * 0.008);

  scattering += vec3(
    0.055,
    0.00015,
    0.0006
  ) * distanceGlow;

  // return the combined result
  vec3 C = fresnel * reflection + scattering;
  fragColor = vec4(aces_tonemap(C * 1.75), 1.0);
}
