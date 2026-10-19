class_name Level
extends Node3D
## Зруйнована вулиця з примітивів, світанок, туман, сніг з попелом, далекі вибухи.

const STREET_HALF_W := 10.0
const HALF_LEN := 68.0

var player_start := Vector3(0, 0.1, 56)
var cover_points: Array[Dictionary] = []   # {pos, dir, owner}
var spawn_points: Array[Vector3] = []
var nav_region: NavigationRegion3D
var env: Environment
var sun: DirectionalLight3D
var snow: GPUParticles3D
var smokes: Array[GPUParticles3D] = []
var follow_target: Node3D

var _mats := {}
var _rng := RandomNumberGenerator.new()
var _arty_timer := 3.0


func _ready() -> void:
	_rng.seed = 1991
	_build_environment()
	nav_region = NavigationRegion3D.new()
	add_child(nav_region)
	_build_ground()
	_build_buildings()
	_build_barricades()
	_build_props()
	_build_bounds()
	_bake_navigation()
	_build_atmosphere()
	Settings.changed.connect(_apply_quality)
	_apply_quality()


func _process(delta: float) -> void:
	if follow_target and is_instance_valid(follow_target) and snow:
		snow.global_position = follow_target.global_position + Vector3(0, 10, 0)
	_arty_timer -= delta
	if _arty_timer <= 0.0:
		_arty_timer = _rng.randf_range(4.0, 11.0)
		_artillery_flash()


# ---------- Освітлення та середовище ----------

func _build_environment() -> void:
	var sky_mat := ProceduralSkyMaterial.new()
	sky_mat.sky_top_color = Color(0.22, 0.27, 0.4)
	sky_mat.sky_horizon_color = Color(0.95, 0.58, 0.38)
	sky_mat.ground_horizon_color = Color(0.45, 0.33, 0.28)
	sky_mat.ground_bottom_color = Color(0.12, 0.1, 0.1)
	sky_mat.sun_angle_max = 20.0
	var sky := Sky.new()
	sky.sky_material = sky_mat
	sky.radiance_size = Sky.RADIANCE_SIZE_64
	env = Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 0.7
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.tonemap_exposure = 1.05
	env.fog_enabled = true
	env.fog_light_color = Color(0.72, 0.6, 0.55)
	env.fog_light_energy = 1.0
	env.fog_density = 0.018
	env.fog_sky_affect = 0.55
	env.glow_intensity = 0.6
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)

	sun = DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-11, 165, 0)
	sun.light_color = Color(1.0, 0.72, 0.5)
	sun.light_energy = 1.25
	sun.directional_shadow_max_distance = 35.0
	sun.directional_shadow_mode = DirectionalLight3D.SHADOW_ORTHOGONAL
	add_child(sun)


func _apply_quality() -> void:
	var q := Settings.quality
	sun.shadow_enabled = q >= 2
	env.glow_enabled = q >= 1
	env.fog_density = [0.022, 0.018, 0.016][q]
	if snow:
		snow.amount = [250, 600, 1200][q]
	for s in smokes:
		s.amount = [12, 24, 40][q]


# ---------- Геометрія ----------

func _mat(c: Color) -> StandardMaterial3D:
	var key := c.to_html()
	if not _mats.has(key):
		var m := StandardMaterial3D.new()
		m.albedo_color = c
		m.roughness = 0.92
		_mats[key] = m
	return _mats[key]


## Статичний бокс з колізією (у навігаційному регіоні).
func box(pos: Vector3, size: Vector3, color: Color, rot := Vector3.ZERO, surface := "concrete",
		vis_range := 0.0) -> StaticBody3D:
	var body := StaticBody3D.new()
	body.collision_layer = 1
	body.collision_mask = 0
	body.position = pos
	body.rotation_degrees = rot
	body.set_meta("surface", surface)
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	mi.mesh = bm
	mi.material_override = _mat(color)
	if vis_range > 0.0:
		mi.visibility_range_end = vis_range
		mi.visibility_range_end_margin = 4.0
		mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	body.add_child(mi)
	var cs := CollisionShape3D.new()
	var sh := BoxShape3D.new()
	sh.size = size
	cs.shape = sh
	body.add_child(cs)
	nav_region.add_child(body)
	return body


## Декоративний меш без колізії.
func deco(pos: Vector3, size: Vector3, color: Color, rot := Vector3.ZERO, vis_range := 70.0) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	mi.mesh = bm
	mi.material_override = _mat(color)
	mi.position = pos
	mi.rotation_degrees = rot
	mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	mi.visibility_range_end = vis_range
	add_child(mi)
	return mi


func _build_ground() -> void:
	box(Vector3(0, -0.1, 0), Vector3(44, 0.2, HALF_LEN * 2 + 4), Color(0.2, 0.2, 0.21))
	for s: float in [-1.0, 1.0]:
		box(Vector3(s * 11.5, 0.07, 0), Vector3(3, 0.14, HALF_LEN * 2), Color(0.38, 0.36, 0.34))
	# розмітка та вибоїни
	for z in range(-64, 66, 8):
		deco(Vector3(0, 0.005, z), Vector3(0.18, 0.01, 3.0), Color(0.6, 0.58, 0.5), Vector3.ZERO, 50.0)
	for i in 10:
		var p := Vector3(_rng.randf_range(-8, 8), 0.004, _rng.randf_range(-60, 60))
		deco(p, Vector3(2.2, 0.01, 2.2), Color(0.1, 0.09, 0.09), Vector3(0, _rng.randf_range(0, 90), 0), 60.0)


func _build_buildings() -> void:
	var wall_cols := [Color(0.55, 0.5, 0.45), Color(0.62, 0.58, 0.52), Color(0.48, 0.44, 0.42), Color(0.58, 0.48, 0.42)]
	for s: float in [-1.0, 1.0]:
		var z := -HALF_LEN + 2.0
		while z < HALF_LEN - 4.0:
			var w := _rng.randf_range(10.0, 15.0)
			var cz := z + w * 0.5
			var col: Color = wall_cols[_rng.randi() % wall_cols.size()]
			var h := _rng.randf_range(6.0, 15.0)
			var x := s * 17.5
			# основний об'єм будинку
			box(Vector3(x, h * 0.5, cz), Vector3(8, h, w), col)
			# зруйнований верх: нерівні уламки
			for k in 3:
				var bh := _rng.randf_range(1.0, 4.0)
				var bw := _rng.randf_range(2.0, w * 0.4)
				var bz := cz + _rng.randf_range(-w * 0.35, w * 0.35)
				box(Vector3(x + s * _rng.randf_range(0, 2), h + bh * 0.5, bz), Vector3(_rng.randf_range(3, 6), bh, bw), col.darkened(0.1),
					Vector3(_rng.randf_range(-6, 6), 0, _rng.randf_range(-8, 8)))
			# вікна (темні прорізи) та сліди пожежі
			var floors := int(h / 3.0)
			for f in floors:
				for wi in int(w / 3.0):
					var wz := cz - w * 0.5 + 1.5 + wi * 3.0
					var burnt := _rng.randf() < 0.25
					deco(Vector3(x - s * 4.02, 1.7 + f * 3.0, wz), Vector3(0.05, 1.4, 1.1),
						Color(0.05, 0.05, 0.05) if not burnt else Color(0.02, 0.02, 0.02), Vector3.ZERO, 90.0)
					if burnt:
						deco(Vector3(x - s * 4.03, 2.9 + f * 3.0, wz), Vector3(0.05, 1.0, 1.6), Color(0.12, 0.11, 0.1), Vector3.ZERO, 90.0)
			# завал біля фасаду
			if _rng.randf() < 0.6:
				_rubble(Vector3(s * 12.3, 0, cz), 2.5, col)
			# провулок між будинками
			z += w + (_rng.randf_range(2.5, 4.0) if _rng.randf() < 0.35 else 0.0)


func _rubble(center: Vector3, radius: float, col: Color) -> void:
	for i in 7:
		var sz := Vector3(_rng.randf_range(0.4, 1.4), _rng.randf_range(0.2, 0.7), _rng.randf_range(0.4, 1.4))
		var p := center + Vector3(_rng.randf_range(-radius, radius) * 0.5, sz.y * 0.4, _rng.randf_range(-radius, radius))
		box(p, sz, col.darkened(_rng.randf_range(0.0, 0.3)),
			Vector3(_rng.randf_range(-20, 20), _rng.randf_range(0, 180), _rng.randf_range(-20, 20)), "concrete", 60.0)


func _sandbag_wall(center: Vector3, length: float, rot_y := 0.0) -> void:
	# колізія — один бокс; мішки — MultiMesh (дешево для GPU)
	var h := 1.05
	box(center + Vector3(0, h * 0.5, 0), Vector3(length, h, 0.7), Color(0, 0, 0, 0), Vector3(0, rot_y, 0)).get_child(0).visible = false
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	var bag := BoxMesh.new()
	bag.size = Vector3(0.6, 0.24, 0.36)
	mm.mesh = bag
	var per_row := int(length / 0.58)
	var rows := 4
	mm.instance_count = per_row * rows * 2
	var basis_wall := Basis(Vector3.UP, deg_to_rad(rot_y))
	var idx := 0
	for r in rows:
		for d in 2:
			for i in per_row:
				var off := Vector3(-length * 0.5 + 0.3 + i * 0.58 + (0.29 if r % 2 == 1 else 0.0), 0.12 + r * 0.25, -0.17 + d * 0.34)
				off.x = minf(off.x, length * 0.5 - 0.3)
				var b := Basis(Vector3.UP, _rng.randf_range(-0.12, 0.12)).scaled(Vector3(1, _rng.randf_range(0.85, 1.1), 1))
				mm.set_instance_transform(idx, Transform3D(basis_wall * b, center + basis_wall * off))
				idx += 1
	var mmi := MultiMeshInstance3D.new()
	mmi.multimesh = mm
	mmi.material_override = _mat(Color(0.52, 0.47, 0.34))
	mmi.visibility_range_end = 90.0
	add_child(mmi)
	# точки укриття з обох боків стіни
	var n := basis_wall * Vector3(0, 0, 1)
	for k in [-0.3, 0.3]:
		var along := basis_wall * Vector3(length * k, 0, 0)
		cover_points.append({"pos": center + along - n * 0.9, "dir": n, "owner": null})
		cover_points.append({"pos": center + along + n * 0.9, "dir": -n, "owner": null})


func _hedgehog(pos: Vector3) -> void:
	var c := Color(0.22, 0.2, 0.18)
	var body := box(pos + Vector3(0, 0.6, 0), Vector3(1.2, 1.2, 1.2), c, Vector3(0, _rng.randf_range(0, 90), 0), "metal")
	body.get_child(0).visible = false
	for r in [Vector3(45, 0, 0), Vector3(0, 0, 45), Vector3(0, 45, 90)]:
		var mi := MeshInstance3D.new()
		var bm := BoxMesh.new()
		bm.size = Vector3(0.15, 1.7, 0.15)
		mi.mesh = bm
		mi.material_override = _mat(c)
		mi.rotation_degrees = r
		mi.visibility_range_end = 80.0
		body.add_child(mi)


func _burned_car(pos: Vector3, rot_y: float, smoke: bool) -> void:
	var c := Color(0.2, 0.14, 0.11)
	var car := box(pos + Vector3(0, 0.6, 0), Vector3(1.8, 0.8, 4.2), c, Vector3(0, rot_y, _rng.randf_range(-4, 4)), "metal")
	var cab := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(1.6, 0.6, 2.2)
	cab.mesh = bm
	cab.material_override = _mat(Color(0.12, 0.1, 0.09))
	cab.position = Vector3(0, 0.7, -0.2)
	car.add_child(cab)
	for wx in [-0.9, 0.9]:
		for wz in [-1.3, 1.3]:
			var wh := MeshInstance3D.new()
			var cyl := CylinderMesh.new()
			cyl.top_radius = 0.33
			cyl.bottom_radius = 0.33
			cyl.height = 0.2
			cyl.radial_segments = 8
			wh.mesh = cyl
			wh.material_override = _mat(Color(0.06, 0.06, 0.06))
			wh.position = Vector3(wx, -0.35, wz)
			wh.rotation_degrees = Vector3(0, 0, 90)
			wh.visibility_range_end = 60.0
			car.add_child(wh)
	var n := Basis(Vector3.UP, deg_to_rad(rot_y)) * Vector3(1, 0, 0)
	cover_points.append({"pos": pos + n * 1.6, "dir": -n, "owner": null})
	cover_points.append({"pos": pos - n * 1.6, "dir": n, "owner": null})
	if smoke:
		var p := Fx.make_particles(30, 7.0, Vector3.UP, 12.0, Vector2(1.2, 2.2), Vector3(0.4, 0.3, 0),
			Color(0.12, 0.11, 0.1, 0.7), Color(0.3, 0.3, 0.3, 0), 2.2, 4.0, false, false)
		p.visibility_aabb = AABB(Vector3(-10, -2, -10), Vector3(20, 40, 20))
		p.position = pos + Vector3(0, 1.2, 0)
		add_child(p)
		p.emitting = true
		smokes.append(p)


func _build_barricades() -> void:
	# Лінії оборони поперек вулиці (від гравця до ворога)
	var lines := [44.0, 28.0, 12.0, -4.0, -20.0, -36.0, -50.0]
	for i in lines.size():
		var z: float = lines[i]
		var side := -1.0 if i % 2 == 0 else 1.0
		_sandbag_wall(Vector3(side * 4.5, 0, z), 6.0)
		_sandbag_wall(Vector3(-side * 6.5, 0, z + _rng.randf_range(-3, 3)), 3.5, _rng.randf_range(-20, 20))
		_hedgehog(Vector3(side * -1.0, 0, z - 2.5))
		if i % 2 == 1:
			_hedgehog(Vector3(side * 1.5, 0, z + 3.0))
		# бетонні блоки
		box(Vector3(-side * 1.5, 0.45, z + 1.5), Vector3(1.8, 0.9, 0.9), Color(0.58, 0.57, 0.55),
			Vector3(0, _rng.randf_range(-25, 25), 0))
		cover_points.append({"pos": Vector3(-side * 1.5, 0, z), "dir": Vector3(0, 0, 1), "owner": null})
	_burned_car(Vector3(-6.5, 0, 34), 15.0, true)
	_burned_car(Vector3(6.0, 0, 5), -30.0, false)
	_burned_car(Vector3(-3.5, 0, -27), 70.0, true)
	_burned_car(Vector3(7.0, 0, -44), 10.0, false)


func _build_props() -> void:
	# стовпи освітлення, частина повалена
	for z in range(-60, 61, 15):
		for s: float in [-1.0, 1.0]:
			var fallen := _rng.randf() < 0.3
			if fallen:
				box(Vector3(s * 7.5, 0.15, z + 3.0), Vector3(0.18, 0.18, 7.0), Color(0.25, 0.25, 0.25),
					Vector3(0, s * 25.0, 0), "metal", 80.0)
			else:
				box(Vector3(s * 10.3, 3.5, z), Vector3(0.18, 7.0, 0.18), Color(0.25, 0.25, 0.25),
					Vector3(0, 0, _rng.randf_range(-6, 6)), "metal", 90.0)
	# уламки посеред вулиці
	for i in 6:
		_rubble(Vector3(_rng.randf_range(-7, 7), 0, _rng.randf_range(-58, 50)), 1.5, Color(0.5, 0.47, 0.43))
	# точки спавну ворогів та патрулювання
	for x in [-7.0, -2.0, 3.0, 8.0]:
		spawn_points.append(Vector3(x, 0.2, -62))
	for z in [-30.0, -10.0, 10.0]:
		spawn_points.append(Vector3(-8.5, 0.2, z))
		spawn_points.append(Vector3(8.5, 0.2, z - 6.0))


func _build_bounds() -> void:
	for s: float in [-1.0, 1.0]:
		var b := box(Vector3(s * 21.5, 10, 0), Vector3(1, 20, HALF_LEN * 2 + 4), Color.BLACK)
		b.get_child(0).queue_free()
		var e := box(Vector3(0, 10, s * (HALF_LEN + 1.5)), Vector3(44, 20, 1), Color.BLACK)
		e.get_child(0).queue_free()
	# візуальний завал у кінці вулиці
	for s: float in [-1.0, 1.0]:
		for i in 5:
			deco(Vector3(_rng.randf_range(-9, 9), _rng.randf_range(0.5, 2.5), s * (HALF_LEN - 0.5)),
				Vector3(_rng.randf_range(2, 5), _rng.randf_range(1, 3), 1.5), Color(0.4, 0.37, 0.34),
				Vector3(_rng.randf_range(-15, 15), _rng.randf_range(-20, 20), _rng.randf_range(-15, 15)), 200.0)


func _bake_navigation() -> void:
	var nm := NavigationMesh.new()
	nm.geometry_parsed_geometry_type = NavigationMesh.PARSED_GEOMETRY_STATIC_COLLIDERS
	nm.geometry_collision_mask = 1
	nm.cell_size = 0.25
	nm.cell_height = 0.25
	nm.agent_radius = 0.5
	nm.agent_height = 2.0
	nm.agent_max_climb = 0.25
	nm.agent_max_slope = 40.0
	nm.filter_baking_aabb = AABB(Vector3(-11.5, -1, -HALF_LEN), Vector3(23, 4, HALF_LEN * 2))
	nav_region.navigation_mesh = nm
	nav_region.bake_navigation_mesh(false)


# ---------- Атмосфера ----------

func _build_atmosphere() -> void:
	snow = Fx.make_particles(600, 7.0, Vector3.DOWN, 20.0, Vector2(0.6, 1.4), Vector3(0.35, -0.6, 0.1),
		Color(0.95, 0.95, 0.95, 0.9), Color(0.3, 0.28, 0.27, 0.8), 0.06, 1.0, false, false)
	var m := snow.process_material as ParticleProcessMaterial
	m.emission_shape = ParticleProcessMaterial.EMISSION_SHAPE_BOX
	m.emission_box_extents = Vector3(22, 1, 22)
	m.color_ramp = null
	var g := Gradient.new()
	g.set_color(0, Color(0.95, 0.95, 0.95, 0.9))   # сніг
	g.set_color(1, Color(0.25, 0.24, 0.23, 0.9))   # попіл
	var gt := GradientTexture1D.new()
	gt.gradient = g
	m.color_initial_ramp = gt
	snow.visibility_aabb = AABB(Vector3(-25, -15, -25), Vector3(50, 20, 50))
	snow.preprocess = 4.0
	add_child(snow)
	snow.global_position = player_start + Vector3(0, 10, 0)
	snow.emitting = true


func _artillery_flash() -> void:
	var side := -1.0 if _rng.randf() < 0.5 else 1.0
	var pos := Vector3(side * _rng.randf_range(30, 45), _rng.randf_range(15, 30), _rng.randf_range(-90, -20))
	var l := OmniLight3D.new()
	l.light_color = Color(1.0, 0.55, 0.25)
	l.omni_range = 70.0
	l.omni_attenuation = 0.6
	l.light_energy = 0.0
	add_child(l)
	l.position = pos
	var tw := create_tween()
	tw.tween_property(l, "light_energy", _rng.randf_range(3.0, 6.0), 0.05)
	tw.parallel().tween_property(env, "fog_light_energy", 1.5, 0.05)
	tw.tween_property(l, "light_energy", 0.0, 0.6)
	tw.parallel().tween_property(env, "fog_light_energy", 1.0, 0.8)
	tw.tween_callback(l.queue_free)
	var listener := follow_target.global_position if follow_target else Vector3.ZERO
	var delay := pos.distance_to(listener) / 340.0
	get_tree().create_timer(delay, false).timeout.connect(func(): Sfx.play("far_boom", null, -6.0, 0.2))


# ---------- API для ворогів ----------

func find_cover(from: Vector3, threat: Vector3, who: Node) -> Dictionary:
	var best: Dictionary = {}
	var best_score := INF
	for c in cover_points:
		var owner_node: Variant = c["owner"]
		if owner_node != null and owner_node != who and is_instance_valid(owner_node):
			continue
		var p: Vector3 = c["pos"]
		var d := p.distance_to(from)
		if d > Config.ENEMY_COVER_SEARCH_RADIUS:
			continue
		var to_threat := threat - p
		if to_threat.length() < 7.0:
			continue
		var dir: Vector3 = c["dir"]
		if dir.dot(to_threat.normalized()) < 0.35:
			continue
		var score := d + _rng.randf_range(0.0, 5.0)
		if score < best_score:
			best_score = score
			best = c
	if not best.is_empty():
		release_cover(who)
		best["owner"] = who
	return best


func release_cover(who: Node) -> void:
	for c in cover_points:
		if c["owner"] == who:
			c["owner"] = null


func random_patrol_point(near_z: float) -> Vector3:
	var z := clampf(near_z + _rng.randf_range(-35.0, -6.0), -60.0, 50.0)
	return Vector3(_rng.randf_range(-8.0, 8.0), 0, z)
