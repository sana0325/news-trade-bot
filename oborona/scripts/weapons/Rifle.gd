class_name Rifle
extends Node3D
## Штурмова гвинтівка: hitscan через RayCast3D, віддача, розкид, спалах, звук.

signal ammo_changed(mag: int, reserve: int)
signal hit_confirmed(headshot: bool, killed: bool)
signal fired

const HIP_POS := Vector3(0.19, -0.18, -0.48)
const ADS_DIST := -0.34
const RIFLE_GLB := "res://assets/weapons/rifle.glb"
const RIFLE_CFG := "res://assets/weapons/rifle.json"
const ARMS_GLB := "res://assets/weapons/arms.glb"
const ARMS_CFG := "res://assets/weapons/arms.json"

## Опорні точки моделі (у просторі _gun_root). Для GLB задаються в rifle.json.
var muzzle_point := RifleMesh.MUZZLE
var sight_point := Vector3(0.0, 0.126, -0.02)
var ads_pos := Vector3(0.0, -0.126, ADS_DIST)

var player: Player
var camera: Camera3D
var ray: RayCast3D
var mag: int = Config.RIFLE_MAG_SIZE
var reserve: int = Config.RIFLE_RESERVE_AMMO
var trigger := false
var ads := false
var moving := false
var crouching := false
var reloading := false
var bloom := 0.0

var _reload_t := 0.0
var _cooldown := 0.0
var _flash_t := 0.0
var _model: Node3D       # поза (позиція/поворот анімуються)
var _gun_root: Node3D    # сама модель зброї + руки
var _reticle: MeshInstance3D
# шари анімації
var _bob_t := 0.0
var _idle_t := 0.0
var _sway := Vector2.ZERO
var _sway_target := Vector2.ZERO
var _rec := Vector3.ZERO     # x — підкид (рад), y — рискання, z — відкат назад (м)
var _rec_v := Vector3.ZERO
var _flash_light: OmniLight3D
var _flash_mesh: MeshInstance3D


func setup(p: Player, cam: Camera3D) -> void:
	player = p
	camera = cam
	ray = RayCast3D.new()
	ray.enabled = false
	ray.collision_mask = 1 | 4
	ray.add_exception(p)
	camera.add_child(ray)
	_build_model()


func _build_model() -> void:
	_model = Node3D.new()
	_model.position = HIP_POS
	add_child(_model)
	_gun_root = Node3D.new()
	_model.add_child(_gun_root)
	var has_glb := ResourceLoader.exists(RIFLE_GLB)
	if has_glb:
		_load_glb(RIFLE_GLB, RIFLE_CFG, true)
	else:
		_build_procedural()
	if ResourceLoader.exists(ARMS_GLB):
		_load_glb(ARMS_GLB, ARMS_CFG, false)
	elif not has_glb:
		_build_procedural_arms()
	ads_pos = Vector3(-sight_point.x, -sight_point.y, ADS_DIST)
	_build_reticle()
	for m in _model.find_children("*", "GeometryInstance3D", true, false):
		(m as GeometryInstance3D).cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	_build_flash()


## Завантажує GLB і застосовує трансформацію з JSON:
## {"scale": 1.0, "rotation": [0,180,0], "position": [0,0,0], "muzzle": [..], "sight": [..]}
func _load_glb(path: String, cfg_path: String, is_rifle: bool) -> void:
	var cfg := {}
	if FileAccess.file_exists(cfg_path):
		var parsed: Variant = JSON.parse_string(FileAccess.get_file_as_string(cfg_path))
		if parsed is Dictionary:
			cfg = parsed
	var inst: Node3D = (load(path) as PackedScene).instantiate()
	var sc: float = cfg.get("scale", 1.0)
	var rot: Array = cfg.get("rotation", [0, 0, 0])
	var pos: Array = cfg.get("position", [0, 0, 0])
	inst.scale = Vector3.ONE * sc
	inst.rotation_degrees = Vector3(rot[0], rot[1], rot[2])
	inst.position = Vector3(pos[0], pos[1], pos[2])
	_gun_root.add_child(inst)
	if is_rifle:
		if cfg.has("muzzle"):
			var m: Array = cfg["muzzle"]
			muzzle_point = Vector3(m[0], m[1], m[2])
		if cfg.has("sight"):
			var s2: Array = cfg["sight"]
			sight_point = Vector3(s2[0], s2[1], s2[2])
	for mi in inst.find_children("*", "GeometryInstance3D", true, false):
		var gi := mi as GeometryInstance3D
		gi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF


func _build_reticle() -> void:
	_reticle = MeshInstance3D.new()
	var q := QuadMesh.new()
	q.size = Vector2(0.04, 0.04)
	_reticle.mesh = q
	var sm := ShaderMaterial.new()
	sm.shader = load("res://scripts/weapons/reticle.gdshader")
	_reticle.material_override = sm
	_reticle.position = sight_point
	_gun_root.add_child(_reticle)


func _build_procedural() -> void:
	var steel := _plain(Color(0.2, 0.2, 0.2), 0.42, 0.6)
	var dark := _plain(Color(0.11, 0.11, 0.11), 0.6, 0.3)
	var poly := _plain(Color(0.5, 0.43, 0.32), 0.8, 0.0)
	var gun := RifleMesh.build(steel, dark, poly)
	_gun_root.add_child(gun)


func _build_procedural_arms() -> void:
	var glove := StandardMaterial3D.new()
	glove.albedo_color = Color(0.2, 0.19, 0.16)
	glove.roughness = 0.9
	glove.disable_receive_shadows = true
	var sleeve := _vm("camo", Color(0.8, 0.82, 0.75), 0.5)
	# руки від рукавичок назад до камери
	for arm in [[RifleMesh.GRIP_RIGHT, Vector3(0.12, -0.3, 0.5)], [RifleMesh.GRIP_LEFT, Vector3(-0.22, -0.3, 0.1)]]:
		var hand: Vector3 = arm[0]
		var elbow: Vector3 = arm[1]
		var len := hand.distance_to(elbow)
		var fore := Node3D.new()
		_gun_root.add_child(fore)
		fore.transform = Transform3D(Basis.looking_at(hand - elbow, Vector3.UP), elbow)
		var c := CylinderMesh.new()
		c.top_radius = 0.042
		c.bottom_radius = 0.05
		c.height = len
		c.radial_segments = 12
		var mi := MeshInstance3D.new()
		mi.mesh = c
		mi.material_override = sleeve
		mi.rotation_degrees = Vector3(-90, 0, 0)
		mi.position = Vector3(0, 0, -len * 0.5)
		fore.add_child(mi)
		var g := MeshInstance3D.new()
		var sp := SphereMesh.new()
		sp.radius = 0.042
		sp.height = 0.084
		g.mesh = sp
		g.material_override = glove
		g.scale = Vector3(0.9, 1.0, 1.35)
		g.position = hand
		_gun_root.add_child(g)


func _build_flash() -> void:
	_flash_mesh = MeshInstance3D.new()
	var q := QuadMesh.new()
	q.size = Vector2(0.18, 0.18)
	var fm := StandardMaterial3D.new()
	fm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	fm.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	fm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	fm.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	fm.albedo_color = Color(1.0, 0.75, 0.35)
	fm.albedo_texture = Fx._mat_add.albedo_texture
	q.material = fm
	_flash_mesh.mesh = q
	_flash_mesh.position = muzzle_point + Vector3(0, 0, -0.06)
	_flash_mesh.visible = false
	_flash_mesh.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	_gun_root.add_child(_flash_mesh)
	_flash_light = OmniLight3D.new()
	_flash_light.light_color = Color(1, 0.7, 0.35)
	_flash_light.omni_range = 6.0
	_flash_light.visible = false
	_flash_light.position = muzzle_point
	_gun_root.add_child(_flash_light)


## Матеріал для моделі в руках: без чужих тіней (не стає чорним силуетом) і з крупнішою текстурою.
func _plain(c: Color, rough: float, metal: float) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_color = c
	m.roughness = rough
	m.metallic = metal
	m.metallic_specular = 0.6
	m.disable_receive_shadows = true
	m.rim_enabled = true
	m.rim = 0.35
	return m


func _vm(name: String, tint: Color, tile: float) -> StandardMaterial3D:
	var m := Mats.get_mat(name, tint, false).duplicate() as StandardMaterial3D
	m.uv1_scale = Vector3.ONE / tile
	m.disable_receive_shadows = true
	m.rim_enabled = true
	m.rim = 0.4
	m.metallic = 0.15
	return m


## Зміщення огляду (пікселі) — для інерції зброї.
func look_input(px: Vector2) -> void:
	_sway_target += px


func _animate(delta: float) -> void:
	var aimed := ads and not reloading
	var target := ads_pos if aimed else HIP_POS
	var weight := 0.25 if aimed else 1.0
	# ходьба: «вісімка» з підскоком
	if moving and player.is_on_floor():
		_bob_t += delta * (7.5 if not crouching else 5.0)
	else:
		_bob_t = lerpf(_bob_t, roundf(_bob_t / PI) * PI, 1.0 - exp(-6.0 * delta))
	var bob := Vector3(sin(_bob_t) * 0.011, -absf(cos(_bob_t)) * 0.013, 0.0) * weight
	# дихання
	_idle_t += delta
	var breath := Vector3(sin(_idle_t * 1.3) * 0.0015, sin(_idle_t * 2.1) * 0.002, 0.0) * weight
	# інерція від повороту камери
	_sway = _sway.lerp((-_sway_target * 0.0009).limit_length(0.09), 1.0 - exp(-10.0 * delta))
	_sway_target = Vector2.ZERO
	# пружинна віддача: швидкий підкид і плавне повернення
	_rec_v += (-_rec * 220.0 - _rec_v * 22.0) * delta
	_rec += _rec_v * delta
	# перезарядка: нахил і поворот, ривок при вставленні магазину, зведення затвора
	var rl := Vector3.ZERO
	var rl_pos := Vector3.ZERO
	if reloading:
		var p := 1.0 - _reload_t / Config.RIFLE_RELOAD_TIME
		var arc := sin(clampf(p / 0.85, 0.0, 1.0) * PI)
		rl = Vector3(-0.55 * arc, 0.35 * arc, 0.55 * arc)
		rl_pos = Vector3(-0.04, -0.06, 0.04) * arc
		if p > 0.48 and p < 0.56:
			rl_pos.y += sin((p - 0.48) / 0.08 * PI) * 0.02   # магазин став на місце
		if p > 0.82 and p < 0.95:
			rl_pos.z += sin((p - 0.82) / 0.13 * PI) * 0.035  # затвор
	var pos_target := target + bob + breath + rl_pos + Vector3(_sway.x * 0.15, -_sway.y * 0.1, _rec.z * 0.02)
	_model.position = _model.position.lerp(pos_target, 1.0 - exp(-20.0 * delta))
	var base_yaw := 0.0 if aimed else deg_to_rad(-5.0)
	var rot_target := Vector3(_rec.x * 0.05 + _sway.y + rl.x, base_yaw + _sway.x + _rec.y * 0.03 + rl.y,
		sin(_bob_t) * 0.025 * weight + _sway.x * 0.6 + rl.z)
	_model.rotation = _model.rotation.lerp(rot_target, 1.0 - exp(-18.0 * delta))


func current_spread() -> float:
	var base := Config.RIFLE_SPREAD_ADS if ads else Config.RIFLE_SPREAD_HIP
	if moving:
		base *= Config.RIFLE_SPREAD_MOVE_MULT
	if crouching:
		base *= Config.RIFLE_SPREAD_CROUCH_MULT
	return base + bloom * (0.4 if ads else 1.0)


func _process(delta: float) -> void:
	_cooldown -= delta
	bloom = maxf(0.0, bloom - Config.RIFLE_SPREAD_RECOVERY * delta)
	if _flash_t > 0.0:
		_flash_t -= delta
		if _flash_t <= 0.0:
			_flash_mesh.visible = false
			_flash_light.visible = false
	if reloading:
		_reload_t -= delta
		if _reload_t <= 0.0:
			_finish_reload()
	elif trigger and _cooldown <= 0.0:
		if mag > 0:
			_shoot()
		else:
			_cooldown = 0.3
			Sfx.play("empty")
			reload()
	_animate(delta)


func _shoot() -> void:
	_cooldown = 1.0 / Config.RIFLE_FIRE_RATE
	mag -= 1
	var spread := deg_to_rad(current_spread())
	var ang := randf() * TAU
	var r := sqrt(randf()) * tan(spread)
	ray.target_position = Vector3(cos(ang) * r, sin(ang) * r, -1.0).normalized() * Config.RIFLE_RANGE
	ray.force_raycast_update()
	if ray.is_colliding():
		var col := ray.get_collider()
		var point := ray.get_collision_point()
		var normal := ray.get_collision_normal()
		if col and col.has_method("apply_damage"):
			var headshot := false
			if col.has_method("is_head_shape"):
				headshot = col.call("is_head_shape", ray.get_collider_shape())
			var dmg := Config.RIFLE_DAMAGE * (Config.RIFLE_HEADSHOT_MULT if headshot else 1.0)
			var killed: bool = col.call("apply_damage", dmg, player.global_position, headshot)
			hit_confirmed.emit(headshot, killed)
			Sfx.play("hit", null, -4.0 if not killed else 0.0)
			Fx.impact(point, normal, "hit")
		elif col:
			var surface: String = col.get_meta("surface", "concrete")
			Fx.impact(point, normal, "sparks" if surface == "metal" else "dust")
	bloom = minf(Config.RIFLE_SPREAD_MAX, bloom + Config.RIFLE_SPREAD_PER_SHOT)
	var rk := Config.RIFLE_RECOIL_ADS_MULT if ads else 1.0
	player.add_recoil(Config.RIFLE_RECOIL_PITCH * rk, randf_range(-Config.RIFLE_RECOIL_YAW, Config.RIFLE_RECOIL_YAW) * rk)
	_rec_v += Vector3(randf_range(2.2, 3.0) * (0.45 if ads else 1.0), randf_range(-0.6, 0.6), 1.6 if ads else 2.4)
	_flash_t = 0.045
	_flash_mesh.visible = true
	_flash_mesh.rotation.z = randf() * TAU
	_flash_light.visible = true
	_flash_light.light_energy = 2.5
	Sfx.play("shot", null, -2.0, 0.05)
	Settings.vibrate(Config.VIBRATE_SHOT)
	fired.emit()
	ammo_changed.emit(mag, reserve)


func reload() -> void:
	if reloading or mag >= Config.RIFLE_MAG_SIZE or reserve <= 0:
		return
	reloading = true
	_reload_t = Config.RIFLE_RELOAD_TIME
	Sfx.play("reload", null, -2.0, 0.0)


func _finish_reload() -> void:
	reloading = false
	var need := Config.RIFLE_MAG_SIZE - mag
	var take := mini(need, reserve)
	mag += take
	reserve -= take
	ammo_changed.emit(mag, reserve)


func add_ammo(n: int) -> void:
	reserve += n
	ammo_changed.emit(mag, reserve)
