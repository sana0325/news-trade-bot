extends Node
## Ефекти частинок (GPUParticles3D). Autoload "Fx".

var _mat_alpha: StandardMaterial3D
var _mat_add: StandardMaterial3D
var _mat_smoke: StandardMaterial3D
var _mat_fire: StandardMaterial3D


func _ready() -> void:
	var g := Gradient.new()
	g.set_color(0, Color(1, 1, 1, 1))
	g.set_color(1, Color(1, 1, 1, 0))
	var tex := GradientTexture2D.new()
	tex.gradient = g
	tex.fill = GradientTexture2D.FILL_RADIAL
	tex.fill_from = Vector2(0.5, 0.5)
	tex.fill_to = Vector2(1.0, 0.5)
	tex.width = 64
	tex.height = 64
	_mat_alpha = _make_mat(tex, false)
	_mat_add = _make_mat(tex, true)
	var flip: Texture2D = load("res://textures/smoke_flipbook.png")
	_mat_smoke = _make_mat(flip, false)
	_mat_fire = _make_mat(flip, true)
	for m in [_mat_smoke, _mat_fire]:
		m.particles_anim_h_frames = 4
		m.particles_anim_v_frames = 4
		m.particles_anim_loop = false


func _make_mat(tex: Texture2D, additive: bool) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	m.vertex_color_use_as_albedo = true
	m.albedo_texture = tex
	if additive:
		m.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	return m


## Створює частинки. Якщо one_shot — вузол сам видаляється.
func make_particles(amount: int, lifetime: float, dir: Vector3, spread: float, vel: Vector2,
		gravity: Vector3, col_a: Color, col_b: Color, size: float, grow: float,
		additive := false, one_shot := true, flip := false) -> GPUParticles3D:
	var p := GPUParticles3D.new()
	p.amount = maxi(1, int(amount * Settings.particle_mult()))
	p.lifetime = lifetime
	p.one_shot = one_shot
	p.explosiveness = 0.95 if one_shot else 0.0
	p.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	var m := ParticleProcessMaterial.new()
	m.direction = dir
	m.spread = spread
	m.initial_velocity_min = vel.x
	m.initial_velocity_max = vel.y
	m.gravity = gravity
	m.damping_min = 0.5
	m.damping_max = 1.5
	m.scale_min = 0.7
	m.scale_max = 1.3
	m.angle_min = 0.0
	m.angle_max = 360.0
	var g := Gradient.new()
	g.set_color(0, col_a)
	g.set_color(1, col_b)
	var gt := GradientTexture1D.new()
	gt.gradient = g
	m.color_ramp = gt
	var c := Curve.new()
	c.max_value = maxf(1.0, grow)
	c.add_point(Vector2(0, 1))
	c.add_point(Vector2(1, grow))
	var ct := CurveTexture.new()
	ct.curve = c
	m.scale_curve = ct
	if flip:
		m.anim_speed_min = 1.0
		m.anim_speed_max = 1.0
		m.anim_offset_max = 0.15
	p.process_material = m
	var q := QuadMesh.new()
	q.size = Vector2(size, size)
	if flip:
		q.material = _mat_fire if additive else _mat_smoke
	else:
		q.material = _mat_add if additive else _mat_alpha
	p.draw_pass_1 = q
	p.visibility_aabb = AABB(Vector3(-10, -10, -10), Vector3(20, 20, 20))
	if one_shot:
		p.emitting = false
	return p


## Пул частинок: ефекти не створюються заново на кожне влучання, а перевикористовуються.
const POOL_SIZE := 8
var _pools := {}


func _emit(key: String, pos: Vector3, dir: Vector3, build: Callable) -> void:
	var scene := get_tree().current_scene
	if scene == null:
		return
	var pool: Array = _pools.get(key, [])
	pool = pool.filter(func(n): return is_instance_valid(n) and n.is_inside_tree())
	_pools[key] = pool
	var p: GPUParticles3D = null
	for c: GPUParticles3D in pool:
		if not c.emitting:
			p = c
			break
	if p == null:
		if pool.size() < POOL_SIZE:
			p = build.call()
			scene.add_child(p)
			pool.append(p)
		else:
			p = pool.pop_front()
			pool.append(p)
	p.global_position = pos
	(p.process_material as ParticleProcessMaterial).direction = dir
	p.restart()


## Влучання: "dust" (бетон), "sparks" (метал), "hit" (ціль).
func impact(pos: Vector3, normal: Vector3, kind := "dust") -> void:
	match kind:
		"sparks":
			_emit("sparks", pos, normal, func(): return make_particles(10, 0.35, normal, 45.0, Vector2(4, 9),
				Vector3(0, -9.8, 0), Color(1, 0.85, 0.4, 1), Color(1, 0.35, 0.1, 0), 0.06, 0.4, true))
			_emit("sparks_smoke", pos, normal, func(): return make_particles(4, 0.7, normal, 25.0, Vector2(0.5, 1.5),
				Vector3(0, 0.5, 0), Color(0.4, 0.4, 0.4, 0.6), Color(0.4, 0.4, 0.4, 0), 0.2, 2.5))
		"hit":
			_emit("hit", pos, normal, func(): return make_particles(8, 0.5, normal, 40.0, Vector2(1, 3),
				Vector3(0, -4, 0), Color(0.35, 0.3, 0.26, 0.9), Color(0.3, 0.28, 0.25, 0), 0.18, 1.5))
		_:
			_emit("dust", pos, normal, func(): return make_particles(10, 0.9, normal, 35.0, Vector2(1, 3),
				Vector3(0, -2, 0), Color(0.62, 0.57, 0.5, 0.85), Color(0.55, 0.52, 0.48, 0), 0.4, 3.0, false, true, true))
			_emit("chips", pos, normal, func(): return make_particles(5, 0.6, normal, 30.0, Vector2(3, 6),
				Vector3(0, -9.8, 0), Color(0.4, 0.37, 0.33, 1), Color(0.4, 0.37, 0.33, 1), 0.05, 1.0))


func explosion(pos: Vector3) -> void:
	_emit("fireball", pos, Vector3.UP, func(): return make_particles(26, 0.5, Vector3.UP, 180.0, Vector2(2, 7),
		Vector3.ZERO, Color(2.5, 1.6, 0.7, 1), Color(0.8, 0.2, 0.05, 0), 1.6, 2.5, true, true, true))
	_emit("boom_smoke", pos, Vector3.UP, func(): return make_particles(22, 3.5, Vector3.UP, 70.0, Vector2(1, 4),
		Vector3(0, 0.6, 0), Color(0.22, 0.21, 0.2, 0.9), Color(0.35, 0.34, 0.33, 0), 2.4, 3.5, false, true, true))
	_emit("debris", pos, Vector3.UP, func(): return make_particles(24, 1.4, Vector3.UP, 60.0, Vector2(4, 11),
		Vector3(0, -9.8, 0), Color(0.35, 0.3, 0.25, 1), Color(0.35, 0.3, 0.25, 0.8), 0.12, 1.0))
	_emit("boom_sparks", pos, Vector3.UP, func(): return make_particles(14, 0.4, Vector3.UP, 90.0, Vector2(8, 16),
		Vector3(0, -9.8, 0), Color(1, 0.8, 0.4, 1), Color(1, 0.4, 0.1, 0), 0.07, 0.5, true))
	var scene := get_tree().current_scene
	if scene is Node3D:
		var l := OmniLight3D.new()
		l.light_color = Color(1, 0.6, 0.3)
		l.omni_range = 12.0
		l.light_energy = 8.0
		scene.add_child(l)
		l.global_position = pos + Vector3.UP
		var tw := l.create_tween()
		tw.tween_property(l, "light_energy", 0.0, 0.45)
		tw.tween_callback(l.queue_free)
