#include "common.glsl"
// Build-time tables, drawn once into tiny textures so the per-pixel passes only fetch:
//   u_mode 0: 128x128 R8 white noise (stochastic rounding of the trail decay, and film grain)
//   u_mode 1: 1024x1 R16F tone curve, display value of linear x = 8 u^2 (filmic shoulder + toe + gamma)
uniform int u_mode;
uniform vec4 u_tone;        // toe, shoulder order (0: exponential), mid contrast, contrast pivot (display)
out vec4 o_col;

float tone(float x) {
    float y = u_tone.y > 0.0 ? 1.0 - pow(1.0 + x / u_tone.y, -u_tone.y)   // softer shoulder: detail in the core
                             : 1.0 - exp(-x);                           // soft shoulder: highlights roll into silver
    y = y * y / (y + u_tone.x) * (1.0 + u_tone.x);                      // gentle toe: the void stays black
    float d = pow(max(y, 0.0), 1.0 / 2.2);
    return d + u_tone.z * d * (1.0 - d) * (d - u_tone.w);               // mid-tone contrast around the pivot
}

void main() {
    ivec2 ip = ivec2(gl_FragCoord.xy);
    if (u_mode == 0) {
        o_col = vec4(hashf(uint(ip.x) * 1973u + uint(ip.y) * 9277u + 26699u), 0.0, 0.0, 1.0);
    } else {
        float u = (float(ip.x) + 0.5) / 1024.0;
        o_col = vec4(tone(8.0 * u * u), 0.0, 0.0, 1.0);
    }
}
