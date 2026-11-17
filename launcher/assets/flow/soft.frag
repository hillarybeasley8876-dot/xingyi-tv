#include "common.glsl"
// Soft layer, quarter resolution. Carries this frame's soft accumulation, decayed, into next
// frame's image; writes RG16F: R = 3x3 tent of the soft layer in linear intensity (R16F tops out at
// 65504, raw counts do not), G = the frame's grade gain (exposure x fade x vignette x ground).
// The final pass upsamples both with one bilinear fetch.
uniform highp usampler2D u_acc;
layout(r32ui, binding = 3) uniform highp writeonly uimage2D u_next;
uniform sampler2D u_noise;
uniform vec2 u_noff;
uniform float u_decay;
uniform float u_scale;      // accumulated units -> linear intensity, times softgain / 16 (tent weights)
uniform float u_gain;       // exposure x fade
uniform float u_vig;
uniform vec3 u_ground;      // darken towards the bottom: y full, y none (0..1 from bottom), strength
uniform vec2 u_sres;
uniform float u_aspect;
out vec4 o_col;

void main() {
    ivec2 ip = ivec2(gl_FragCoord.xy);
    ivec2 hi = textureSize(u_acc, 0) - 1;
    uint n = texelFetch(u_acc, ip, 0).r;
    if (n != 0u) {          // empty texels skip the carry, as in final.frag
        float r = texture(u_noise, gl_FragCoord.xy * (1.0 / 128.0) + u_noff).r;
        imageStore(u_next, ip, uvec4(uint(float(n) * u_decay + r)));
    }
    float s = 4.0 * float(n);
    s += 2.0 * (float(texelFetch(u_acc, clamp(ip + ivec2(1, 0), ivec2(0), hi), 0).r)
              + float(texelFetch(u_acc, clamp(ip - ivec2(1, 0), ivec2(0), hi), 0).r)
              + float(texelFetch(u_acc, clamp(ip + ivec2(0, 1), ivec2(0), hi), 0).r)
              + float(texelFetch(u_acc, clamp(ip - ivec2(0, 1), ivec2(0), hi), 0).r));
    s += float(texelFetch(u_acc, clamp(ip + ivec2(1, 1), ivec2(0), hi), 0).r)
       + float(texelFetch(u_acc, clamp(ip + ivec2(-1, 1), ivec2(0), hi), 0).r)
       + float(texelFetch(u_acc, clamp(ip + ivec2(1, -1), ivec2(0), hi), 0).r)
       + float(texelFetch(u_acc, clamp(ip - ivec2(1, 1), ivec2(0), hi), 0).r);
    vec2 uv = gl_FragCoord.xy / u_sres;
    vec2 v = (uv - 0.5) * vec2(u_aspect, 1.0);
    float g = u_gain * (1.0 - u_vig * smoothstep(0.35, 1.15, length(v)))
                     * (1.0 - u_ground.z * (1.0 - smoothstep(u_ground.x, u_ground.y, uv.y)));
    o_col = vec4(s * u_scale, g, 0.0, 1.0);
}
