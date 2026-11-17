// Shared by every Orrery flow shader. The loader prepends the platform header
// (#version 320 es + precision on the TV, #version 430 on the PC) and expands #include.
uniform float u_time;
uniform float u_dt;
uniform int u_frame;

uint hashu(uint x) {
    x ^= x >> 16; x *= 0x7feb352du;
    x ^= x >> 15; x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}
float hashf(uint x) { return float(hashu(x) >> 8) * (1.0 / 16777216.0); }
