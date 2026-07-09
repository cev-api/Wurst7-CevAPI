float noise( in vec3 x )
{
    vec3 i = floor(x);
    vec3 f = fract(x);
	f = f*f*(3.0-2.0*f);
	vec2 uv = (i.xy+vec2(37.0,17.0)*i.z) + f.xy;
	vec2 rg = textureLod( iChannel0, (uv+0.5)/256.0, 0.0 ).yx;
	return mix( rg.x, rg.y, f.z );
}

float mapTerrain( vec3 p )
{
	p *= 0.1; 
	p.xz *= 0.6;
	
	float time = 0.5 + 0.15*iTime;
	float ft = fract( time );
	float it = floor( time );
	ft = smoothstep( 0.7, 1.0, ft );
	time = it + ft;
	float spe = 1.4;
	
	float f;
    f  = 0.5000*noise( p*1.00 + vec3(0.0,1.0,0.0)*spe*time );
    f += 0.2500*noise( p*2.02 + vec3(0.0,2.0,0.0)*spe*time );
    f += 0.1250*noise( p*4.01 );
	return 25.0*f-10.0;
}

vec3 gro = vec3(0.0);

float map(in vec3 c) 
{
	vec3 p = c + 0.5;
	
	float f = mapTerrain( p ) + 0.25*p.y;

    f = mix( f, 1.0, step( length(gro-p), 5.0 ) );

	return step( f, 0.5 );
}


float hash13( vec3 p3 )
{
    p3 = fract( p3*0.1031 );
    p3 += dot( p3, p3.zyx + 31.32 );
    return fract( (p3.x + p3.y)*p3.z );
}

vec3 blockTexture( in vec3 vos, in vec3 nor, in vec3 uvw )
{
    const float TEX = 16.0;


    vec2 uv;
    if     ( abs(nor.y)>0.5 ) uv = uvw.xz;
    else if( abs(nor.x)>0.5 ) uv = uvw.zy;
    else                      uv = uvw.xy;

    vec2 texel = clamp( floor(uv*TEX), 0.0, TEX-1.0 );

    float n = hash13( vec3(texel, 13.7) );

    bool airAbove = map( vos + vec3(0.0,1.0,0.0) ) < 0.5;

    vec3 grass = vec3(0.23,0.46,0.10)*(0.80+0.40*n);
    vec3 dirt  = vec3(0.36,0.23,0.13)*(0.80+0.40*n);
    vec3 stone = vec3(0.42,0.42,0.44)*(0.75+0.45*n);

    vec3 col;
    if( airAbove )
    {
        if( nor.y> 0.5 )      col = grass;
        else if( nor.y<-0.5 ) col = dirt;
        else
        {

            float rim = TEX - 3.0 - floor( 2.0*hash13(vec3(texel.x, 5.1, 9.3)) );
            col = ( texel.y >= rim ) ? grass : dirt;
        }
    }
    else
    {
        float b = hash13( vos*1.7 + 0.31 );   
        col = ( b < 0.20 ) ? dirt : stone;

        if( b > 0.93 )
        {
            float o = hash13( vec3(texel, floor(b*997.0)) );
            if( o > 0.72 )
                col = ( b > 0.975 ) ? vec3(0.95,0.75,0.25)*(0.8+0.3*n)  // gold
                                    : vec3(0.10,0.10,0.12);             // coal
            else
                col = stone;
        }
    }
    return col;
}


const vec3 lig = normalize( vec3(-0.4,0.3,0.7) );

float raycast( in vec3 ro, in vec3 rd, out vec3 oVos, out vec3 oDir )
{
	vec3 pos = floor(ro);
	vec3 ri = 1.0/rd;
	vec3 rs = sign(rd);
	vec3 dis = (pos-ro + 0.5 + rs*0.5) * ri;
	
	float res = -1.0;
	vec3 mm = vec3(0.0);
	for( int i=0; i<128; i++ ) 
	{
		if( map(pos)>0.5 ) { res=1.0; break; }
        
		mm = step(dis.xyz, dis.yzx) * step(dis.xyz, dis.zxy);
		dis += mm * rs * ri;
        pos += mm * rs;
	}

	vec3 nor = -mm*rs;
	vec3 vos = pos;
	

	vec3 mini = (pos-ro + 0.5 - 0.5*vec3(rs))*ri;
	float t = max ( mini.x, max ( mini.y, mini.z ) );
	
	oDir = mm;
	oVos = vos;

	return t*res;
}

vec3 path( float t, float ya )
{
    vec2 p  = 100.0*sin( 0.02*t*vec2(1.0,1.2) + vec2(0.1,0.9) );
	     p +=  50.0*sin( 0.04*t*vec2(1.3,1.0) + vec2(1.0,4.5) );
	
	return vec3( p.x, 18.0 + ya*4.0*sin(0.05*t), p.y );
}

mat3 setCamera( in vec3 ro, in vec3 ta, float cr )
{
	vec3 cw = normalize(ta-ro);
	vec3 cp = vec3(sin(cr), cos(cr),0.0);
	vec3 cu = normalize( cross(cw,cp) );
	vec3 cv = normalize( cross(cu,cw) );
    return mat3( cu, cv, -cw );
}

float maxcomp( in vec4 v )
{
    return max( max(v.x,v.y), max(v.z,v.w) );
}

float isEdge( in vec2 uv, vec4 va, vec4 vb, vec4 vc, vec4 vd )
{
    vec2 st = 1.0 - uv;

    vec4 wb = smoothstep( 0.85, 0.99, vec4(uv.x,
                                           st.x,
                                           uv.y,
                                           st.y) ) * ( 1.0 - va + va*vc );
    vec4 wc = smoothstep( 0.85, 0.99, vec4(uv.x*uv.y,
                                           st.x*uv.y,
                                           st.x*st.y,
                                           uv.x*st.y) ) * ( 1.0 - vb + vd*vb );
    return maxcomp( max(wb,wc) );
}


vec3 render( in vec3 ro, in vec3 rd )
{
    vec3 fogCol = vec3(0.16,0.28,0.55);
    vec3 col;
	
	vec3 vos, dir;
	float t = raycast( ro, rd, vos, dir );
	if( t>0.0 )
	{
        vec3 nor = -dir*sign(rd);
        vec3 pos = ro + rd*t;
        vec3 uvw = pos - vos;
		
		vec3 v1  = vos + nor + dir.yzx;
	    vec3 v2  = vos + nor - dir.yzx;
	    vec3 v3  = vos + nor + dir.zxy;
	    vec3 v4  = vos + nor - dir.zxy;
		vec3 v5  = vos + nor + dir.yzx + dir.zxy;
        vec3 v6  = vos + nor - dir.yzx + dir.zxy;
	    vec3 v7  = vos + nor - dir.yzx - dir.zxy;
	    vec3 v8  = vos + nor + dir.yzx - dir.zxy;
	    vec3 v9  = vos + dir.yzx;
	    vec3 v10 = vos - dir.yzx;
	    vec3 v11 = vos + dir.zxy;
	    vec3 v12 = vos - dir.zxy;
 	    vec3 v13 = vos + dir.yzx + dir.zxy; 
	    vec3 v14 = vos - dir.yzx + dir.zxy ;
	    vec3 v15 = vos - dir.yzx - dir.zxy;
	    vec3 v16 = vos + dir.yzx - dir.zxy;

		vec4 vc = vec4( map(v1),  map(v2),  map(v3),  map(v4)  );
	    vec4 vd = vec4( map(v5),  map(v6),  map(v7),  map(v8)  );
	    vec4 va = vec4( map(v9),  map(v10), map(v11), map(v12) );
	    vec4 vb = vec4( map(v13), map(v14), map(v15), map(v16) );
		
		vec2 uv = vec2( dot(dir.yzx, uvw), dot(dir.zxy, uvw) );
	
        float www = 1.0 - isEdge( uv, va, vb, vc, vd );

        // minecraft block texture
        col = blockTexture( vos, nor, uvw );
        col *= 1.0 - 0.25*www;
		
        float dif = clamp( dot( nor, lig ), 0.0, 1.0 );
        float bac = clamp( dot( nor, normalize(lig*vec3(-1.0,0.0,-1.0)) ), 0.0, 1.0 );
        float sky = 0.5 + 0.5*nor.y;
        float amb = clamp(0.75 + pos.y/25.0,0.0,1.0);
        float occ = 1.0;
	
        vec2 st = 1.0 - uv;
        vec4 wa = vec4( uv.x, st.x, uv.y, st.y ) * vc;
        vec4 wb = vec4(uv.x*uv.y,
                       st.x*uv.y,
                       st.x*st.y,
                       uv.x*st.y)*vd*(1.0-vc.xzyw)*(1.0-vc.zywx);
        occ = wa.x + wa.y + wa.z + wa.w +
              wb.x + wb.y + wb.z + wb.w;
           
        occ = 1.0 - occ/8.0;
        occ = occ*occ;
        occ = occ*occ;
        occ *= amb;

        vec3 lin = vec3(0.0);
        lin += 2.0*dif*vec3(1.05,0.90,0.70)*(0.5+0.5*occ);
        lin += 0.30*bac*vec3(0.25,0.20,0.15)*occ;
        lin += 0.45*sky*vec3(0.35,0.50,0.85)*occ;     
        lin += 0.05*occ;

        col = col*lin;

        col = mix( col, fogCol, 1.0-exp(-0.006*t) );
	}
    else
    {
        col = mix( fogCol, vec3(0.05,0.12,0.38), clamp(rd.y*1.4,0.0,1.0) );
        float sun = clamp( dot(rd,lig), 0.0, 1.0 );
        col += 0.6*vec3(1.0,0.7,0.35)*pow(sun,6.0);
        col += 2.0*vec3(1.0,0.85,0.55)*pow(sun,256.0);
    }

    col *= 0.75;

    col = clamp( (col*(2.51*col+0.03)) / (col*(2.43*col+0.59)+0.14), 0.0, 1.0 );

    col = pow( col, vec3(1.0/2.2) );

    float lum = dot( col, vec3(0.2126,0.7152,0.0722) );
    col = mix( vec3(lum), col, 1.18 );

    return col;
}

void mainImage( out vec4 fragColor, in vec2 fragCoord )
{	
    vec2 p = (2.0*fragCoord-iResolution.xy)/iResolution.y;
    vec2 mo = iMouse.xy / iResolution.xy;
    if( iMouse.z<=0.00001 ) mo=vec2(0.0);
	float time = 2.0*iTime + 50.0*mo.x;
    
	float cr = 0.2*cos(0.1*iTime);
	vec3 ro = path( time+0.0, 1.0 );
	vec3 ta = path( time+5.0, 1.0 ) - vec3(0.0,6.0,0.0);
	gro = ro;

    mat3 cam = setCamera( ro, ta, cr );
	
    float r2 = p.x*p.x*0.32 + p.y*p.y;
    p *= (7.0-sqrt(37.5-11.5*r2))/(r2+1.0);
    vec3 rd = normalize( cam * vec3(p.xy,-2.5) );

    vec3 col = render( ro, rd );
    
	vec2 q = fragCoord / iResolution.xy;
	col *= 0.5 + 0.5*pow( 16.0*q.x*q.y*(1.0-q.x)*(1.0-q.y), 0.1 );
	
	fragColor = vec4( col, 1.0 );
}

void mainVR( out vec4 fragColor, in vec2 fragCoord, in vec3 fragRayOri, in vec3 fragRayDir )
{
	float time = 1.0*iTime;

    float cr = 0.0;
	vec3 ro = path( time+0.0, 0.0 ) + vec3(0.0,0.7,0.0);
	vec3 ta = path( time+2.5, 0.0 ) + vec3(0.0,0.7,0.0);

    mat3 cam = setCamera( ro, ta, cr );

    vec3 col = render( ro + cam*fragRayOri, cam*fragRayDir );
    
    fragColor = vec4( col, 1.0 );
}
