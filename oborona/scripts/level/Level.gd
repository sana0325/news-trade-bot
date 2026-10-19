class_name Level
extends Node3D
## Зруйнована вулиця: PBR-текстури, об'єднана геометрія (мало draw calls), світанок, туман,
## сніг з попелом, далекі вибухи.

const HALF_LEN := 68.0
const CHUNK := 40.0

var player_start := Vector3(0, 0.1, 56)
var cover_points: Array[Dictionary] = []   # {pos, dir, owner}
var spawn_points: Array[Vector3] = []
var nav_region: NavigationRegion3D
var env: Environment
var sun: DirectionalLight3D
var snow: GPUParticles3D
var smokes: Array[GPUParticles3D] = []
var follow_target: Node3D

var _batches := {}   # ключ -> {st: SurfaceTool, mat: Material}
var _rng := RandomNumberGenerator.new()
var _arty_timer := 3.0
var _arty_light: OmniLight3D


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
	_commit_batches()
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
	sky_mat.sky_top_color = Color(0.2, 0.26, 0.4)
	sky_mat.sky_horizon_color = Color(0.92, 0.6, 0.42)
	sky_mat.sky_curve = 0.12
	sky_mat.ground_horizon_color = Color(0.5, 0.38, 0.32)
	sky_mat.ground_bottom_color = Color(0.12, 0.1, 0.1)
	sky_mat.sun_angle_max = 25.0
	sky_mat.sun_curve = 0.08
	var sky := Sky.new()
	sky.sky_material = sky_mat
	sky.radiance_size = Sky.RADIANCE_SIZE_64
	env = Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 0.75
	env.reflected_light_source = Environment.REFLECTION_SOURCE_SKY
	env.tonemap_mode = Environment.TONE_MAPPER_ACES
	env.tonemap_exposure = 1.1
	env.tonemap_white = 6.0
	env.fog_enabled = true
	env.fog_light_color = Color(0.7, 0.58, 0.52)
	env.fog_light_energy = 1.0
	env.fog_sun_scatter = 0.35
	env.fog_density = 0.016
	env.fog_aerial_perspective = 0.5
	env.fog_sky_affect = 0.35
	env.fog_height = 1.5
	env.fog_height_density = 0.035
	env.glow_intensity = 0.5
	env.glow_bloom = 0.05
	env.glow_hdr_threshold = 1.2
	env.adjustment_enabled = true
	env.adjustment_contrast = 1.08
	env.adjustment_saturation = 0.88
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)

	sun = DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-14, 160, 0)
	sun.light_color = Color(1.0, 0.74, 0.52)
	sun.light_energy = 1.6
	sun.shadow_bias = 0.04
	sun.shadow_normal_bias = 1.5
	sun.shadow_blur = 1.5
	add_child(sun)

	_arty_light = OmniLight3D.new()
	_arty_light.light_color = Color(1.0, 0.55, 0.25)
	_arty_light.omni_range = 70.0
	_arty_light.omni_attenuation = 0.6
	_arty_light.light_energy = 0.0
	_arty_light.visible = false
	add_child(_arty_light)


func _apply_quality() -> void:
	var q := Settings.quality
	sun.shadow_enabled = q >= 1
	sun.directional_shadow_mode = DirectionalLight3D.SHADOW_PARALLEL_2_SPLITS if q >= 2 else DirectionalLight3D.SHADOW_ORTHOGONAL
	sun.directional_shadow_max_distance = [20.0, 22.0, 40.0][q]
	env.glow_enabled = q >= 1
	env.fog_density = [0.016, 0.011, 0.009][q]
	if snow:
		snow.amount = [200, 500, 1000][q]
	for s in smokes:
		s.amount = [10, 20, 34][q]


# ---------- Об'єднання геометрії ----------

const _FACES := [
	[Vector3.RIGHT, Vector3.UP, Vector3.BACK],
	[Vector3.LEFT, Vector3.BACK, Vector3.UP],
	[Vector3.UP, Vector3.BACK, Vector3.RIGHT],
	[Vector3.DOWN, Vector3.RIGHT, Vector3.BACK],
	[Vector3.BACK, Vector3.RIGHT, Vector3.UP],
	[Vector3.FORWARD, Vector3.UP, Vector3.RIGHT],
]


## Додає бокс до спільного меша (за матеріалом і ділянкою вулиці).
func _batch_box(xf: Transform3D, size: Vector3, mat: Material) -> void:
	var key := "%d|%d" % [mat.get_instance_id(), int(floor((xf.origin.z + 80.0) / CHUNK))]
	if not _batches.has(key):
		var st0 := SurfaceTool.new()
		st0.begin(Mesh.PRIMITIVE_TRIANGLES)
		_batches[key] = {"st": st0, "mat": mat}
	var st: SurfaceTool = _batches[key]["st"]
	var h := size * 0.5
	for f in _FACES:
		var n: Vector3 = f[0]
		var u: Vector3 = f[1]
		var v: Vector3 = f[2]
		var c := n * h
		var uu := u * h
		var vv := v * h
		var a := xf * (c - uu - vv)
		var b := xf * (c + uu - vv)
		var cc := xf * (c + uu + vv)
		var d := xf * (c - uu + vv)
		st.set_normal((xf.basis * n).normalized())
		# Godot: лицьова грань — за годинниковою стрілкою
		for p in [a, cc, b, a, d, cc]:
			st.add_vertex(p)


func _commit_batches() -> void:
	for key in _batches:
		var b: Dictionary = _batches[key]
		var st: SurfaceTool = b["st"]
		var mi := MeshInstance3D.new()
		mi.mesh = st.commit()
		mi.material_override = b["mat"]
		add_child(mi)
	_batches.clear()


func _xf(pos: Vector3, rot_deg: Vector3) -> Transform3D:
	var r := rot_deg * (PI / 180.0)
	return Transform3D(Basis.from_euler(r), pos)


## Статичний бокс з колізією + видима частина в спільному меші.
func box(pos: Vector3, size: Vector3, mat: Material, rot := Vector3.ZERO, surface := "concrete",
		visual := true) -> StaticBody3D:
	var body := StaticBody3D.new()
	body.collision_layer = 1
	body.collision_mask = 0
	body.position = pos
	body.rotation_degrees = rot
	body.set_meta("surface", surface)
	var cs := CollisionShape3D.new()
	var sh := BoxShape3D.new()
	sh.size = size
	cs.shape = sh
	body.add_child(cs)
	nav_region.add_child(body)
	if visual and mat:
		_batch_box(_xf(pos, rot), size, mat)
	return body


## Лише візуальний бокс (без колізії).
func deco(pos: Vector3, size: Vector3, mat: Material, rot := Vector3.ZERO) -> void:
	_batch_box(_xf(pos, rot), size, mat)


# ---------- Геометрія ----------

func _build_ground() -> void:
	var asphalt := Mats.get_mat("asphalt")
	box(Vector3(0, -0.1, 0), Vector3(44, 0.2, HALF_LEN * 2 + 4), asphalt)
	var pave := Mats.get_mat("pavement", Color(0.95, 0.93, 0.9))
	var curb := Mats.get_mat("concrete", Color(0.85, 0.84, 0.82))
	for s: float in [-1.0, 1.0]:
		box(Vector3(s * 11.6, 0.07, 0), Vector3(2.8, 0.14, HALF_LEN * 2), pave)
		deco(Vector3(s * 10.15, 0.09, 0), Vector3(0.22, 0.18, HALF_LEN * 2), curb)
	var paint := Mats.get_mat("concrete", Color(0.95, 0.92, 0.78))
	for z in range(-64, 66, 8):
		if _rng.randf() < 0.75:
			deco(Vector3(0, 0.003, z), Vector3(0.15, 0.006, 3.0), paint)
	# вирви від снарядів
	var scorch := Mats.get_mat("asphalt", Color(0.35, 0.33, 0.32))
	for i in 9:
		var p := Vector3(_rng.randf_range(-8, 8), 0.002, _rng.randf_range(-60, 50))
		for k in 3:
			deco(p, Vector3(2.6 - k * 0.6, 0.004 + k * 0.002, 2.6 - k * 0.6), scorch, Vector3(0, _rng.randf_range(0, 90), 0))
		_rubble(p + Vector3(1.2, 0, 0), 0.8, "asphalt", Color(0.8, 0.8, 0.8), 3)


func _wall_mat() -> Array:
	# [матеріал фасаду, матеріал оздоблення]
	var r := _rng.randf()
	if r < 0.35:
		return [Mats.get_mat("brick", [Color.WHITE, Color(0.85, 0.82, 0.8), Color(0.75, 0.7, 0.68)][_rng.randi() % 3]),
			Mats.get_mat("concrete")]
	elif r < 0.75:
		var tints := [Color(1, 1, 1), Color(0.95, 0.9, 0.82), Color(0.88, 0.9, 0.95), Color(1.0, 0.92, 0.85)]
		return [Mats.get_mat("plaster", tints[_rng.randi() % tints.size()]), Mats.get_mat("concrete", Color(0.9, 0.9, 0.9))]
	return [Mats.get_mat("concrete", Color(0.92, 0.92, 0.9)), Mats.get_mat("concrete", Color(0.75, 0.75, 0.74))]


func _build_buildings() -> void:
	var dark := Mats.get_mat("concrete", Color(0.06, 0.06, 0.06))
	var soot := Mats.get_mat("concrete", Color(0.15, 0.14, 0.13))
	var rail := Mats.get_mat("rust", Color(0.7, 0.7, 0.7))
	for s: float in [-1.0, 1.0]:
		var z := -HALF_LEN + 2.0
		while z < HALF_LEN - 4.0:
			var w := _rng.randf_range(10.0, 15.0)
			var cz := z + w * 0.5
			var mats := _wall_mat()
			var wall: Material = mats[0]
			var trim: Material = mats[1]
			var floors := _rng.randi_range(2, 5)
			var h := floors * 3.0 + 0.6
			var x := s * 17.5
			var face_x := x - s * 4.0   # площина фасаду
			# будинок з 2–3 вертикальних секцій різної висоти (зруйнований силует)
			var sections := _rng.randi_range(2, 3)
			var sw := w / sections
			var sec_h: Array[float] = []
			for k in sections:
				var hh := h if _rng.randf() < 0.55 else h - _rng.randf_range(2.0, 6.0)
				hh = maxf(hh, 3.5)
				sec_h.append(hh)
				var sz := cz - w * 0.5 + sw * (k + 0.5)
				box(Vector3(x, hh * 0.5, sz), Vector3(8, hh, sw), wall)
				# зламаний край перекриття
				if hh < h:
					for j in 3:
						deco(Vector3(x - s * _rng.randf_range(0, 3), hh + 0.1, sz + _rng.randf_range(-sw * 0.4, sw * 0.4)),
							Vector3(_rng.randf_range(1, 3), 0.25, _rng.randf_range(0.6, 1.8)), trim,
							Vector3(_rng.randf_range(-12, 12), _rng.randf_range(0, 90), _rng.randf_range(-12, 12)))
				else:
					deco(Vector3(x, hh + 0.15, sz), Vector3(8.3, 0.3, sw), trim)   # карниз
			# цоколь
			deco(Vector3(face_x - s * 0.05, 0.45, cz), Vector3(0.12, 0.9, w), trim)
			# міжповерхові карнизи, вікна, балкони
			for f in floors:
				var fy := 3.0 * (f + 1)
				for wi in int(w / 2.6):
					var wz := cz - w * 0.5 + 1.3 + wi * 2.6
					var k := clampi(int((wz - (cz - w * 0.5)) / sw), 0, sections - 1)
					if fy - 1.2 > sec_h[k]:
						continue
					if fy < sec_h[k]:
						deco(Vector3(face_x - s * 0.06, fy - 0.05, wz), Vector3(0.2, 0.18, 2.6), trim)
					var wy := fy - 1.6
					var burnt := _rng.randf() < 0.3
					deco(Vector3(face_x - s * 0.02, wy, wz), Vector3(0.1, 1.4, 1.1), dark)
					deco(Vector3(face_x - s * 0.12, wy - 0.75, wz), Vector3(0.22, 0.08, 1.3), trim)  # підвіконня
					deco(Vector3(face_x - s * 0.05, wy + 0.75, wz), Vector3(0.1, 0.1, 1.25), trim)   # перемичка
					if burnt:
						deco(Vector3(face_x - s * 0.03, wy + 1.1, wz), Vector3(0.06, 0.7, 1.5), soot)
					if f >= 1 and _rng.randf() < 0.12:
						deco(Vector3(face_x - s * 0.6, wy - 0.85, wz), Vector3(1.2, 0.15, 2.0), trim)   # балкон
						deco(Vector3(face_x - s * 1.17, wy - 0.4, wz), Vector3(0.05, 0.8, 2.0), rail)
			if _rng.randf() < 0.65:
				_rubble(Vector3(s * 12.4, 0, cz), 2.5, "concrete", Color(0.85, 0.85, 0.83), 7)
				_rubble(Vector3(s * 12.6, 0, cz + 1.0), 1.5, "brick", Color.WHITE, 4)
			z += w + (_rng.randf_range(2.5, 4.0) if _rng.randf() < 0.35 else 0.0)


func _rubble(center: Vector3, radius: float, mat_name: String, tint: Color, count: int) -> void:
	for i in count:
		var sz := Vector3(_rng.randf_range(0.3, 1.3), _rng.randf_range(0.15, 0.6), _rng.randf_range(0.3, 1.3))
		var p := center + Vector3(_rng.randf_range(-radius, radius) * 0.5, sz.y * 0.35, _rng.randf_range(-radius, radius))
		var m := Mats.get_mat(mat_name, tint)
		var rot := Vector3(_rng.randf_range(-20, 20), _rng.randf_range(0, 180), _rng.randf_range(-20, 20))
		if sz.y < 0.3:
			deco(p, sz, m, rot)   # дрібне — без колізії
		else:
			box(p, sz, m, rot)


func _sandbag_wall(center: Vector3, length: float, rot_y := 0.0) -> void:
	var h := 1.05
	box(center + Vector3(0, h * 0.5, 0), Vector3(length, h, 0.7), null, Vector3(0, rot_y, 0), "concrete", false)
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	var bag := SphereMesh.new()   # сплюснена сфера — схожа на мішок
	bag.radius = 0.5
	bag.height = 1.0
	bag.radial_segments = 10
	bag.rings = 5
	mm.mesh = bag
	var per_row := int(length / 0.56)
	var rows := 4
	mm.instance_count = per_row * rows * 2
	var basis_wall := Basis(Vector3.UP, deg_to_rad(rot_y))
	var idx := 0
	for r in rows:
		for d in 2:
			for i in per_row:
				var off := Vector3(-length * 0.5 + 0.3 + i * 0.56 + (0.28 if r % 2 == 1 else 0.0), 0.13 + r * 0.25, -0.17 + d * 0.34)
				off.x = minf(off.x, length * 0.5 - 0.3)
				var b := Basis(Vector3.UP, _rng.randf_range(-0.15, 0.15)) * Basis(Vector3.FORWARD, _rng.randf_range(-0.06, 0.06))
				b = b.scaled(Vector3(0.62, _rng.randf_range(0.24, 0.3), 0.38))
				mm.set_instance_transform(idx, Transform3D(basis_wall * b, center + basis_wall * off))
				idx += 1
	var mmi := MultiMeshInstance3D.new()
	mmi.multimesh = mm
	mmi.material_override = Mats.get_mat("sandbag")
	add_child(mmi)
	var n := basis_wall * Vector3(0, 0, 1)
	for k in [-0.3, 0.3]:
		var along := basis_wall * Vector3(length * k, 0, 0)
		cover_points.append({"pos": center + along - n * 0.9, "dir": n, "owner": null})
		cover_points.append({"pos": center + along + n * 0.9, "dir": -n, "owner": null})


func _hedgehog(pos: Vector3) -> void:
	var m := Mats.get_mat("rust", Color(0.5, 0.48, 0.47))
	var yaw := _rng.randf_range(0, 90)
	box(pos + Vector3(0, 0.6, 0), Vector3(1.2, 1.2, 1.2), null, Vector3(0, yaw, 0), "metal", false)
	var base := _xf(pos + Vector3(0, 0.6, 0), Vector3(0, yaw, 0))
	for r in [Vector3(45, 0, 0), Vector3(0, 0, 45), Vector3(0, 45, 90)]:
		var xf := base * _xf(Vector3.ZERO, r)
		_batch_box(xf, Vector3(0.14, 1.75, 0.14), m)


func _burned_car(pos: Vector3, rot_y: float, smoke: bool) -> void:
	var body_m := Mats.get_mat("rust")
	var dark := Mats.get_mat("metal", Color(0.4, 0.38, 0.36))
	var tilt := _rng.randf_range(-4, 4)
	box(pos + Vector3(0, 0.62, 0), Vector3(1.8, 0.75, 4.3), body_m, Vector3(0, rot_y, tilt), "metal")
	var base := _xf(pos + Vector3(0, 0.62, 0), Vector3(0, rot_y, tilt))
	_batch_box(base * _xf(Vector3(0, 0.62, -0.2), Vector3.ZERO), Vector3(1.6, 0.5, 2.1), body_m)       # кабіна
	_batch_box(base * _xf(Vector3(0, 0.62, -0.2), Vector3.ZERO), Vector3(1.62, 0.32, 1.9), dark)        # вигорілі вікна
	for wx in [-0.88, 0.88]:
		for wz in [-1.35, 1.35]:
			_batch_box(base * _xf(Vector3(wx, -0.38, wz), Vector3.ZERO), Vector3(0.22, 0.55, 0.55), dark)
	var n := Basis(Vector3.UP, deg_to_rad(rot_y)) * Vector3(1, 0, 0)
	cover_points.append({"pos": pos + n * 1.6, "dir": -n, "owner": null})
	cover_points.append({"pos": pos - n * 1.6, "dir": n, "owner": null})
	if smoke:
		var p := Fx.make_particles(24, 7.0, Vector3.UP, 12.0, Vector2(1.2, 2.2), Vector3(0.4, 0.3, 0),
			Color(0.1, 0.09, 0.09, 0.65), Color(0.3, 0.3, 0.3, 0), 2.4, 4.0, false, false)
		p.visibility_aabb = AABB(Vector3(-10, -2, -10), Vector3(20, 40, 20))
		p.position = pos + Vector3(0, 1.2, 0)
		add_child(p)
		p.emitting = true
		smokes.append(p)


func _build_barricades() -> void:
	var block := Mats.get_mat("concrete", Color(0.95, 0.95, 0.93))
	var lines := [44.0, 28.0, 12.0, -4.0, -20.0, -36.0, -50.0]
	for i in lines.size():
		var z: float = lines[i]
		var side := -1.0 if i % 2 == 0 else 1.0
		_sandbag_wall(Vector3(side * 4.5, 0, z), 6.0)
		_sandbag_wall(Vector3(-side * 6.5, 0, z + _rng.randf_range(-3, 3)), 3.5, _rng.randf_range(-20, 20))
		_hedgehog(Vector3(side * -1.0, 0, z - 2.5))
		if i % 2 == 1:
			_hedgehog(Vector3(side * 1.5, 0, z + 3.0))
		box(Vector3(-side * 1.5, 0.45, z + 1.5), Vector3(1.8, 0.9, 0.9), block, Vector3(0, _rng.randf_range(-25, 25), 0))
		cover_points.append({"pos": Vector3(-side * 1.5, 0, z), "dir": Vector3(0, 0, 1), "owner": null})
	_burned_car(Vector3(-6.5, 0, 34), 15.0, true)
	_burned_car(Vector3(6.0, 0, 5), -30.0, false)
	_burned_car(Vector3(-3.5, 0, -27), 70.0, true)
	_burned_car(Vector3(7.0, 0, -44), 10.0, false)


func _build_props() -> void:
	var pole := Mats.get_mat("concrete", Color(0.8, 0.8, 0.78))
	var wire := Mats.get_mat("metal", Color(0.3, 0.3, 0.3))
	var prev_top := {}
	for z in range(-60, 61, 15):
		for s: float in [-1.0, 1.0]:
			if _rng.randf() < 0.3:
				box(Vector3(s * 7.5, 0.15, z + 3.0), Vector3(0.24, 0.24, 7.0), pole, Vector3(0, s * 25.0, 0))
				prev_top.erase(s)
			else:
				var lean := _rng.randf_range(-5, 5)
				box(Vector3(s * 10.3, 3.5, z), Vector3(0.24, 7.0, 0.24), pole, Vector3(0, 0, lean))
				var top := Vector3(s * 10.3 - sin(deg_to_rad(lean)) * 3.4, 6.8, z)
				# провислі дроти між стовпами
				if prev_top.has(s):
					var a: Vector3 = prev_top[s]
					var seg := 6
					for k in seg:
						var t0 := float(k) / seg
						var t1 := float(k + 1) / seg
						var p0 := a.lerp(top, t0) - Vector3(0, sin(t0 * PI) * 1.2, 0)
						var p1 := a.lerp(top, t1) - Vector3(0, sin(t1 * PI) * 1.2, 0)
						var mid := (p0 + p1) * 0.5
						var xf := Transform3D(Basis.looking_at(p1 - p0, Vector3.UP), mid)
						_batch_box(xf, Vector3(0.03, 0.03, p0.distance_to(p1)), wire)
				prev_top[s] = top
	for i in 6:
		_rubble(Vector3(_rng.randf_range(-7, 7), 0, _rng.randf_range(-58, 50)), 1.5, "concrete", Color(0.85, 0.84, 0.82), 6)
	for x in [-7.0, -2.0, 3.0, 8.0]:
		spawn_points.append(Vector3(x, 0.2, -62))
	for z in [-30.0, -10.0, 10.0]:
		spawn_points.append(Vector3(-8.5, 0.2, z))
		spawn_points.append(Vector3(8.5, 0.2, z - 6.0))


func _build_bounds() -> void:
	for s: float in [-1.0, 1.0]:
		box(Vector3(s * 21.5, 10, 0), Vector3(1, 20, HALF_LEN * 2 + 4), null, Vector3.ZERO, "concrete", false)
		box(Vector3(0, 10, s * (HALF_LEN + 1.5)), Vector3(44, 20, 1), null, Vector3.ZERO, "concrete", false)
	# завали в кінцях вулиці
	for s: float in [-1.0, 1.0]:
		for i in 7:
			var m := Mats.get_mat("concrete" if i % 2 == 0 else "brick", Color(0.85, 0.83, 0.8))
			deco(Vector3(_rng.randf_range(-10, 10), _rng.randf_range(0.5, 2.5), s * (HALF_LEN - 0.5)),
				Vector3(_rng.randf_range(2, 5), _rng.randf_range(1, 3), 1.5), m,
				Vector3(_rng.randf_range(-15, 15), _rng.randf_range(-20, 20), _rng.randf_range(-15, 15)))


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
	snow = Fx.make_particles(500, 7.0, Vector3.DOWN, 20.0, Vector2(0.6, 1.4), Vector3(0.35, -0.6, 0.1),
		Color(0.95, 0.95, 0.95, 0.9), Color(0.3, 0.28, 0.27, 0.8), 0.05, 1.0, false, false)
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
	_arty_light.position = pos
	_arty_light.visible = true
	var tw := create_tween()
	tw.tween_property(_arty_light, "light_energy", _rng.randf_range(3.0, 6.0), 0.05)
	tw.parallel().tween_property(env, "fog_light_energy", 1.5, 0.05)
	tw.tween_property(_arty_light, "light_energy", 0.0, 0.6)
	tw.parallel().tween_property(env, "fog_light_energy", 1.0, 0.8)
	tw.tween_callback(func(): _arty_light.visible = false)
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
