class_name Rifle
extends Node3D
## Штурмова гвинтівка: hitscan через RayCast3D, віддача, розкид, спалах, звук.

signal ammo_changed(mag: int, reserve: int)
signal hit_confirmed(headshot: bool, killed: bool)
signal fired

const HIP_POS := Vector3(0.2, -0.2, -0.42)
const ADS_POS := Vector3(0.0, -0.118, -0.3)

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
var _kick := 0.0
var _flash_t := 0.0
var _model: Node3D
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
	var dark := StandardMaterial3D.new()
	dark.albedo_color = Color(0.09, 0.09, 0.08)
	dark.roughness = 0.6
	dark.metallic = 0.4
	var olive := StandardMaterial3D.new()
	olive.albedo_color = Color(0.28, 0.27, 0.2)
	var parts := [
		[Vector3(0, 0, 0), Vector3(0.06, 0.08, 0.4), dark],          # ствольна коробка
		[Vector3(0, 0.012, -0.36), Vector3(0.025, 0.025, 0.36), dark], # ствол
		[Vector3(0, -0.005, -0.27), Vector3(0.07, 0.07, 0.2), olive], # цівка
		[Vector3(0, -0.1, -0.06), Vector3(0.045, 0.16, 0.08), dark],  # магазин
		[Vector3(0, -0.08, 0.1), Vector3(0.04, 0.1, 0.05), olive],    # руків'я
		[Vector3(0, -0.02, 0.3), Vector3(0.05, 0.09, 0.22), olive],   # приклад
		[Vector3(0, 0.06, 0.02), Vector3(0.03, 0.04, 0.1), dark],     # приціл
	]
	for p in parts:
		var mi := MeshInstance3D.new()
		var bm := BoxMesh.new()
		bm.size = p[1]
		mi.mesh = bm
		mi.position = p[0]
		mi.material_override = p[2]
		mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		_model.add_child(mi)
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
	_flash_mesh.position = Vector3(0, 0.012, -0.58)
	_flash_mesh.visible = false
	_flash_mesh.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	_model.add_child(_flash_mesh)
	_flash_light = OmniLight3D.new()
	_flash_light.light_color = Color(1, 0.7, 0.35)
	_flash_light.omni_range = 6.0
	_flash_light.light_energy = 0.0
	_flash_light.position = Vector3(0, 0, -0.65)
	_model.add_child(_flash_light)


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
			_flash_light.light_energy = 0.0
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
	var target := ADS_POS if ads and not reloading else HIP_POS
	target.z += _kick
	_model.position = _model.position.lerp(target, 1.0 - exp(-18.0 * delta))
	var rx := -0.6 if reloading else 0.0
	_model.rotation.x = lerpf(_model.rotation.x, rx, 1.0 - exp(-10.0 * delta))
	_kick = move_toward(_kick, 0.0, 0.6 * delta)


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
	_kick = 0.05
	_flash_t = 0.045
	_flash_mesh.visible = true
	_flash_mesh.rotation.z = randf() * TAU
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
