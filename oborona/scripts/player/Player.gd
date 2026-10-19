class_name Player
extends CharacterBody3D
## Гравець: рух, огляд, присід, стрибок, ADS, гранати, FPV-дрон, автоприціл-асист.

signal health_changed(value: float, max_value: float)
signal damaged(from_pos: Vector3)
signal died
signal pause_requested
signal drone_changed(active: bool)
signal grenades_changed(count: int)

var touch: TouchControls
var head: Node3D
var camera: Camera3D
var rifle: Rifle
var collision: CollisionShape3D
var capsule: CapsuleShape3D

var health: float = Config.PLAYER_MAX_HEALTH
var dead := false
var crouching := false
var ads := false
var pitch := 0.0
var recoil := Vector2.ZERO   # x — тангаж, y — рискання (радіани)
var grenades: int = Config.GRENADE_COUNT
var grenade_aiming := false
var drone: Drone
var drone_cooldown := 0.0

var _regen_timer := 0.0
var _jump := false
var _shake := 0.0
var _mobile := false
var _traj: MeshInstance3D
var _traj_mesh: ImmediateMesh
var _traj_mat: StandardMaterial3D
var _land_marker: MeshInstance3D


func _ready() -> void:
	_mobile = OS.has_feature("mobile")
	add_to_group("player")
	add_to_group("damageable")
	collision_layer = 2
	collision_mask = 1 | 4
	floor_snap_length = 0.3
	capsule = CapsuleShape3D.new()
	capsule.radius = Config.PLAYER_RADIUS
	capsule.height = Config.PLAYER_HEIGHT
	collision = CollisionShape3D.new()
	collision.shape = capsule
	collision.position.y = Config.PLAYER_HEIGHT * 0.5
	add_child(collision)
	head = Node3D.new()
	head.position.y = Config.PLAYER_HEIGHT - 0.15
	add_child(head)
	camera = Camera3D.new()
	camera.fov = Config.FOV_HIP
	camera.near = 0.04
	camera.far = 260.0
	head.add_child(camera)
	camera.current = true
	rifle = Rifle.new()
	camera.add_child(rifle)
	rifle.setup(self, camera)
	_build_trajectory()


func setup_touch(t: TouchControls) -> void:
	touch = t
	touch.pressed.connect(_on_pressed)
	touch.released.connect(_on_released)


# ---------- Введення ----------

func _on_pressed(n: String) -> void:
	if dead:
		return
	if drone:
		if n == "fire":
			drone.detonate()
		elif n == "pause":
			pause_requested.emit()
		return
	match n:
		"fire": rifle.trigger = true
		"ads": ads = not ads
		"reload": rifle.reload()
		"crouch": crouching = not crouching
		"jump": _jump = true
		"grenade": _start_grenade()
		"drone": activate_drone()
		"pause": pause_requested.emit()


func _on_released(n: String) -> void:
	match n:
		"fire": rifle.trigger = false
		"grenade": _throw_grenade()


func release_inputs() -> void:
	rifle.trigger = false
	if grenade_aiming:
		grenade_aiming = false
		_traj.visible = false
		_land_marker.visible = false


## Клавіатура/миша для тестування на ПК.
func _unhandled_input(event: InputEvent) -> void:
	if _mobile or dead:
		return
	if event is InputEventMouseMotion and Input.mouse_mode == Input.MOUSE_MODE_CAPTURED:
		_look((event as InputEventMouseMotion).relative * 0.6)
	elif event is InputEventMouseButton:
		var mb := event as InputEventMouseButton
		if Input.mouse_mode != Input.MOUSE_MODE_CAPTURED:
			if mb.pressed:
				Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
			return
		if mb.button_index == MOUSE_BUTTON_LEFT:
			if mb.pressed:
				_on_pressed("fire")
			else:
				_on_released("fire")
		elif mb.button_index == MOUSE_BUTTON_RIGHT and mb.pressed:
			_on_pressed("ads")
	elif event is InputEventKey and not event.is_echo():
		var k := event as InputEventKey
		var map := {KEY_R: "reload", KEY_C: "crouch", KEY_SPACE: "jump", KEY_G: "grenade", KEY_F: "drone", KEY_ESCAPE: "pause"}
		if map.has(k.physical_keycode):
			if k.pressed:
				_on_pressed(map[k.physical_keycode])
			else:
				_on_released(map[k.physical_keycode])


func _keyboard_move() -> Vector2:
	if _mobile:
		return Vector2.ZERO
	var v := Vector2.ZERO
	if Input.is_physical_key_pressed(KEY_W): v.y -= 1
	if Input.is_physical_key_pressed(KEY_S): v.y += 1
	if Input.is_physical_key_pressed(KEY_A): v.x -= 1
	if Input.is_physical_key_pressed(KEY_D): v.x += 1
	return v.limit_length(1.0)


func _look(px: Vector2) -> void:
	if drone:
		drone.steer(px)
		return
	var k := Settings.look_sensitivity * (Config.ADS_LOOK_MULT if ads else 1.0)
	rotation.y -= deg_to_rad(px.x * k)
	pitch = clampf(pitch - deg_to_rad(px.y * k), deg_to_rad(-85), deg_to_rad(85))


# ---------- Цикл ----------

func _process(delta: float) -> void:
	if dead:
		return
	if touch:
		_look(touch.take_look_delta())
	if not drone:
		_aim_assist(delta)
	recoil = recoil.lerp(Vector2.ZERO, 1.0 - exp(-Config.RIFLE_RECOIL_RECOVERY * delta))
	_shake = move_toward(_shake, 0.0, delta * 4.0)
	var sh := Vector2(randf_range(-1, 1), randf_range(-1, 1)) * _shake * 0.01
	head.rotation.x = pitch
	camera.rotation = Vector3(recoil.x + sh.x, recoil.y + sh.y, 0)
	camera.fov = lerpf(camera.fov, Config.FOV_ADS if ads else Config.FOV_HIP, 1.0 - exp(-12.0 * delta))
	if grenade_aiming:
		_update_trajectory()


func _physics_process(delta: float) -> void:
	if dead:
		return
	var mv := _keyboard_move()
	if touch:
		mv = (mv + touch.move_vector).limit_length(1.0)
	if drone:
		drone.set_input(mv)
		mv = Vector2.ZERO
	var speed := Config.PLAYER_CROUCH_SPEED if crouching else Config.PLAYER_WALK_SPEED
	if ads:
		speed *= Config.PLAYER_ADS_SPEED_MULT
	var dir := global_transform.basis * Vector3(mv.x, 0, mv.y)
	dir.y = 0
	var target := dir * speed
	velocity.x = move_toward(velocity.x, target.x, Config.PLAYER_ACCEL * delta)
	velocity.z = move_toward(velocity.z, target.z, Config.PLAYER_ACCEL * delta)
	if is_on_floor():
		if _jump:
			velocity.y = Config.PLAYER_JUMP_VELOCITY
			crouching = false
	else:
		velocity.y -= Config.GRAVITY * delta
	_jump = false
	move_and_slide()
	# присід
	var h := Config.PLAYER_CROUCH_HEIGHT if crouching else Config.PLAYER_HEIGHT
	capsule.height = lerpf(capsule.height, h, 1.0 - exp(-12.0 * delta))
	collision.position.y = capsule.height * 0.5
	head.position.y = capsule.height - 0.15
	rifle.moving = mv.length() > 0.15
	rifle.crouching = crouching
	rifle.ads = ads
	# регенерація
	_regen_timer -= delta
	if _regen_timer <= 0.0 and health < Config.PLAYER_MAX_HEALTH:
		health = minf(Config.PLAYER_MAX_HEALTH, health + Config.HEALTH_REGEN_RATE * delta)
		health_changed.emit(health, Config.PLAYER_MAX_HEALTH)
	if drone_cooldown > 0.0:
		drone_cooldown -= delta


func _aim_assist(delta: float) -> void:
	if not Settings.aim_assist or not (rifle.trigger or ads):
		return
	var fwd := -camera.global_basis.z
	var origin := camera.global_position
	var best: Node3D = null
	var best_ang := deg_to_rad(Config.AIM_ASSIST_ANGLE)
	var best_point := Vector3.ZERO
	for e in get_tree().get_nodes_in_group("enemies"):
		var en := e as Node3D
		var p := en.global_position + Vector3(0, 1.25, 0)
		var to := p - origin
		if to.length() > Config.AIM_ASSIST_RANGE:
			continue
		var a := fwd.angle_to(to)
		if a < best_ang:
			best_ang = a
			best = en
			best_point = p
	if best == null:
		return
	var q := PhysicsRayQueryParameters3D.create(origin, best_point, 1)
	if not get_world_3d().direct_space_state.intersect_ray(q).is_empty():
		return
	var to := best_point - head.global_position
	var want_yaw := atan2(-to.x, -to.z)
	var want_pitch := atan2(to.y, Vector2(to.x, to.z).length())
	var k := clampf(Config.AIM_ASSIST_STRENGTH * delta, 0.0, 1.0)
	rotation.y = lerp_angle(rotation.y, want_yaw, k)
	pitch = lerpf(pitch, want_pitch, k)


# ---------- Шкода ----------

func apply_damage(amount: float, from_pos: Vector3, _headshot := false) -> bool:
	if dead:
		return false
	health -= amount
	_regen_timer = Config.HEALTH_REGEN_DELAY
	health_changed.emit(maxf(health, 0.0), Config.PLAYER_MAX_HEALTH)
	damaged.emit(from_pos)
	Settings.vibrate(Config.VIBRATE_DAMAGE)
	shake(0.6)
	if health <= 0.0:
		dead = true
		release_inputs()
		if drone:
			drone.detonate()
		var tw := create_tween()
		tw.tween_property(head, "position:y", 0.3, 0.6)
		tw.parallel().tween_property(head, "rotation:z", 1.2, 0.6)
		died.emit()
		return true
	return false


func add_recoil(pitch_deg: float, yaw_deg: float) -> void:
	recoil += Vector2(deg_to_rad(pitch_deg), deg_to_rad(yaw_deg))


func shake(amount: float) -> void:
	_shake = maxf(_shake, amount)


# ---------- Гранати ----------

func _build_trajectory() -> void:
	_traj_mesh = ImmediateMesh.new()
	_traj = MeshInstance3D.new()
	_traj.mesh = _traj_mesh
	_traj.top_level = true
	_traj.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	_traj.visible = false
	_traj_mat = StandardMaterial3D.new()
	_traj_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	_traj_mat.albedo_color = Color(1.0, 0.85, 0.3)
	_traj_mat.no_depth_test = true
	add_child(_traj)
	_land_marker = MeshInstance3D.new()
	var tm := TorusMesh.new()
	tm.inner_radius = 0.45
	tm.outer_radius = 0.55
	tm.rings = 16
	tm.ring_segments = 4
	_land_marker.mesh = tm
	_land_marker.material_override = _traj_mat
	_land_marker.top_level = true
	_land_marker.visible = false
	add_child(_land_marker)


func _throw_params() -> Array:
	var fwd := -camera.global_basis.z
	var start := camera.global_position + fwd * 0.5 + camera.global_basis.x * 0.15
	var vel := fwd * Config.GRENADE_THROW_SPEED + Vector3.UP * Config.GRENADE_THROW_UP + velocity * 0.5
	return [start, vel]


func _start_grenade() -> void:
	if grenades <= 0 or drone:
		return
	grenade_aiming = true
	_traj.visible = true


func _update_trajectory() -> void:
	var tp := _throw_params()
	var p: Vector3 = tp[0]
	var v: Vector3 = tp[1]
	var space := get_world_3d().direct_space_state
	_traj_mesh.clear_surfaces()
	_traj_mesh.surface_begin(Mesh.PRIMITIVE_LINE_STRIP, _traj_mat)
	_traj_mesh.surface_add_vertex(p)
	var dt := 0.04
	_land_marker.visible = false
	for i in 90:
		var np := p + v * dt
		v.y -= Config.GRAVITY * dt
		var q := PhysicsRayQueryParameters3D.create(p, np, 1)
		var hit := space.intersect_ray(q)
		if not hit.is_empty():
			var hp: Vector3 = hit["position"]
			_traj_mesh.surface_add_vertex(hp)
			_land_marker.visible = true
			_land_marker.global_position = hp + Vector3.UP * 0.05
			break
		_traj_mesh.surface_add_vertex(np)
		p = np
	_traj_mesh.surface_end()


func _throw_grenade() -> void:
	if not grenade_aiming:
		return
	grenade_aiming = false
	_traj.visible = false
	_land_marker.visible = false
	if grenades <= 0 or dead:
		return
	grenades -= 1
	grenades_changed.emit(grenades)
	var tp := _throw_params()
	var g := Grenade.new()
	get_parent().add_child(g)
	g.global_position = tp[0]
	g.linear_velocity = tp[1]
	g.angular_velocity = Vector3(randf(), randf(), randf()) * 8.0


func add_grenades(n: int) -> void:
	grenades = mini(grenades + n, Config.GRENADE_COUNT + 2)
	grenades_changed.emit(grenades)


# ---------- FPV-дрон ----------

func activate_drone() -> void:
	if drone or drone_cooldown > 0.0 or dead:
		return
	release_inputs()
	drone = Drone.new()
	drone.yaw = rotation.y
	drone.pitch = -0.1
	get_parent().add_child(drone)
	drone.global_position = head.global_position + Vector3.UP * 1.5 - global_basis.z * 1.0
	drone.finished.connect(_on_drone_finished)
	drone_changed.emit(true)


func _on_drone_finished() -> void:
	drone = null
	drone_cooldown = Config.DRONE_COOLDOWN
	camera.current = true
	drone_changed.emit(false)
