shader_type spatial;
// Голографічна сітка коліматора: точка + кільце «на нескінченності».
// Позиція сітки залежить від напрямку погляду крізь скло, а не від UV,
// тому точка лишається на цілі навіть коли зброя гойдається.
render_mode unshaded, blend_add, cull_disabled, depth_draw_never, shadows_disabled;

uniform vec4 color : source_color = vec4(1.0, 0.12, 0.06, 1.0);
uniform float intensity = 4.0;
uniform float dot_size = 0.0035;   // тангенс кутового радіуса точки
uniform float ring_radius = 0.032;
uniform float ring_width = 0.0016;

varying vec3 v_local_ray;

void vertex() {
	vec3 cam_local = (inverse(MODEL_MATRIX) * vec4(CAMERA_POSITION_WORLD, 1.0)).xyz;
	v_local_ray = VERTEX - cam_local;
}

void fragment() {
	vec3 d = normalize(v_local_ray);
	vec2 a = d.xy / max(-d.z, 0.0001);
	float r = length(a);
	float dot_m = 1.0 - smoothstep(dot_size * 0.6, dot_size, r);
	float ring_m = 1.0 - smoothstep(0.0, ring_width, abs(r - ring_radius));
	float m = max(dot_m, ring_m * 0.85);
	ALBEDO = color.rgb * intensity * m;
	ALPHA = m;
}
