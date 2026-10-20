// final.frag — combina escena y buffer de luz (§10).
// Luz cuantizada en 5 bandas con tramado Bayer 4×4 entre bandas.
// Compatible con GLES 2: la matriz Bayer llega como textura 4×4 en repetición.
#ifdef GL_ES
precision mediump float;
#endif

uniform sampler2D u_texture;   // escena a 480×270
uniform sampler2D u_light;     // buffer de luz a 480×270
uniform sampler2D u_bayer;     // 4×4, valores m/16
uniform vec2 u_res;
uniform vec3 u_shadow;         // en la sombra la paleta se desplaza al azul del bioma

varying vec2 v_uv;

void main() {
    vec3 col = texture2D(u_texture, v_uv).rgb;
    float l = texture2D(u_light, v_uv).r;
    float d = texture2D(u_bayer, v_uv * u_res / 4.0).r;
    float band = clamp(floor(l * 4.0 + d), 0.0, 4.0) / 4.0;
    vec3 dark = col * u_shadow;
    gl_FragColor = vec4(mix(dark, col, band), 1.0);
}
