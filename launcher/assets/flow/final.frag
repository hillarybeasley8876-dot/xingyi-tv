#include "common.glsl"
// Final pass, full resolution, kept to a handful of operations per pixel (Mali-G31: every ALU
// op here costs about 0.3 ms at 1080p). Carries this frame's sharp accumulation, decayed, into next
// frame's image, adds the upsampled soft layer, and grades through the tone table. Tint mixes the
// two layers' colours by their share; highlights lean to white.
// Empty pixels (most of the screen) stop early. They skip the carry too: if nothing is here now,
// next frame's image holds at most a couple of units left from two frames ago, which decay away.
uniform highp usampler2D u_acc;
layout(r32ui, binding = 2) uniform highp writeonly uimage2D u_next;
uniform sampler2D u_soft;   // RG16F, quarter resolution: soft intensity, grade gain
uniform sampler2D u_lut;    // tone table over u = sqrt(x / 8)
uniform sampler2D u_noise;
uniform vec2 u_noff;
uniform float u_decay;
uniform float u_norm;       // accumulated units -> linear intensity
uniform vec3 u_tsharp;      // tint of the in-focus layer
uniform vec3 u_tsoft;       // tint of the soft layer (out of focus, haze): the one cold whisper
uniform float u_grain;      // grain amplitude, 0..1 display units
in vec2 v_uv;
out vec4 o_col;

void main() {
    ivec2 ip = ivec2(gl_FragCoord.xy);
    uint n = texelFetch(u_acc, ip, 0).r;
    float r = 0.5;
    if (n != 0u) {
        r = texture(u_noise, gl_FragCoord.xy * (1.0 / 128.0) + u_noff).r;
        imageStore(u_next, ip, uvec4(uint(float(n) * u_decay + r)));
    }
    vec2 sg = texture(u_soft, v_uv).rg;
    float tot = float(n) * u_norm + sg.r;
    float x = tot * sg.g;
    if (x < 2e-4) {
        o_col = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    float tone = texture(u_lut, vec2(sqrt(x * 0.125), 0.5)).r;
    vec3 tint = mix(u_tsharp, u_tsoft, sg.r / tot);
    vec3 c = mix(tint, vec3(1.0), tone * tone) * tone;
    o_col = vec4(c + (r - 0.5) * u_grain, 1.0);
}
