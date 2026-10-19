class_name Enemy
extends CharacterBody3D
## Ворог-піхотинець: патруль → помітив → укриття → стрільба (+ пошук гравця).

signal died(enemy: Enemy)

enum State { PATROL, ALERT, COVER, SHOOT, HUNT, DEAD }

var state := State.PATROL
var health: float = Config.ENEMY_HEALTH
var player: Player
var level: Level
var agent: NavigationAgent3D
var patrol_target := Vector3.ZERO
var last_known := Vector3.ZERO
var spread: float = Config.ENEMY_BASE_SPREAD
var cover: Dictionary = {}

var _visual: Node3D
var _head_shape: CollisionShape3D
var _muzzle: OmniLight3D
var _state_time := 0.0
var _sees := false
var _los_timer := 0.0
var _lost_time := 0.0
var _fire_timer := 0.0
var _burst_left := 0
var _relocate := 8.0
var _nav_target := Vector3(INF, INF, INF)
var _stuck_t := 0.0


func _ready() -> void:
	add_to_group("enemies")
	add_to_group("damageable")
	collision_layer = 4
	collision_mask = 1 | 2
	var body_cs := CollisionShape3D.new()
	var cap := CapsuleShape3D.new()
	cap.radius = 0.32
	cap.height = 1.5
	body_cs.shape = cap
	body_cs.position.y = 0.78
	add_child(body_cs)
	_head_shape = CollisionShape3D.new()
	var sph := SphereShape3D.new()
	sph.radius = 0.16
	_head_shape.shape = sph
	_head_shape.position.y = 1.66
	add_child(_head_shape)
	agent = NavigationAgent3D.new()
	agent.path_desired_distance = 1.0
	agent.target_desired_distance = 1.0
	agent.path_max_distance = 3.0
	agent.path_height_offset = 0.5
	add_child(agent)
	_build_visual()
	patrol_target = global_position
	_fire_timer = randf_range(0.3, 1.0)


func _build_visual() -> void:
	_visual = Node3D.new()
	add_child(_visual)
	var uniform := Mats.get_mat("camo", Color.WHITE, false)
	var gear := Mats.get_mat("sandbag", Color(0.45, 0.45, 0.38), false)
	var helmet := Mats.get_mat("camo", Color(0.7, 0.7, 0.65), false)
	var gun := Mats.get_mat("metal", Color.WHITE, false)
	var skin := StandardMaterial3D.new()
	skin.albedo_color = Color(0.55, 0.45, 0.38)
	skin.roughness = 0.7
	var parts := [
		[CapsuleMesh.new(), Vector3(0, 1.15, 0), Vector3(0.5, 0.65, 0.3), uniform],  # тулуб
		[BoxMesh.new(), Vector3(0, 1.2, 0), Vector3(0.46, 0.45, 0.32), gear],        # жилет
		[BoxMesh.new(), Vector3(-0.12, 0.45, 0), Vector3(0.17, 0.85, 0.2), uniform], # ноги
		[BoxMesh.new(), Vector3(0.12, 0.45, 0), Vector3(0.17, 0.85, 0.2), uniform],
		[SphereMesh.new(), Vector3(0, 1.64, 0), Vector3(0.24, 0.26, 0.24), skin],    # голова
		[SphereMesh.new(), Vector3(0, 1.72, 0), Vector3(0.3, 0.2, 0.3), helmet],       # шолом
		[BoxMesh.new(), Vector3(0.18, 1.25, -0.35), Vector3(0.06, 0.08, 0.7), gun], # зброя
	]
	for p in parts:
		var mi := MeshInstance3D.new()
		var mesh: PrimitiveMesh = p[0]
		if mesh is BoxMesh:
			(mesh as BoxMesh).size = Vector3.ONE
		elif mesh is SphereMesh:
			(mesh as SphereMesh).radial_segments = 10
			(mesh as SphereMesh).rings = 6
		elif mesh is CapsuleMesh:
			(mesh as CapsuleMesh).radial_segments = 8
			(mesh as CapsuleMesh).rings = 2
			(mesh as CapsuleMesh).radius = 0.5
			(mesh as CapsuleMesh).height = 1.4
		mi.mesh = mesh
		mi.position = p[1]
		mi.scale = p[2]
		mi.material_override = p[3]
		mi.visibility_range_end = 120.0
		_visual.add_child(mi)
	_muzzle = OmniLight3D.new()
	_muzzle.light_color = Color(1, 0.7, 0.35)
	_muzzle.omni_range = 4.0
	_muzzle.light_energy = 0.0
	_muzzle.visible = false
	_muzzle.position = Vector3(0.18, 1.25, -0.8)
	_visual.add_child(_muzzle)


func is_head_shape(shape_idx: int) -> bool:
	var owner_id := shape_find_owner(shape_idx)
	return shape_owner_get_owner(owner_id) == _head_shape


func _set_state(s: State) -> void:
	state = s
	_state_time = 0.0


func _physics_process(delta: float) -> void:
	if state == State.DEAD:
		return
	_state_time += delta
	if _muzzle.visible:
		_muzzle.light_energy = move_toward(_muzzle.light_energy, 0.0, delta * 40.0)
		_muzzle.visible = _muzzle.light_energy > 0.0
	if not is_on_floor():
		velocity.y -= Config.GRAVITY * delta
	_los_timer -= delta
	if _los_timer <= 0.0:
		_los_timer = 0.15
		_sees = _can_see_player()
	if _sees:
		last_known = player.global_position
		_lost_time = 0.0
		spread = maxf(Config.ENEMY_MIN_SPREAD, spread - Config.ENEMY_SPREAD_DECAY * delta)
	else:
		_lost_time += delta
		spread = minf(Config.ENEMY_BASE_SPREAD, spread + Config.ENEMY_SPREAD_DECAY * 0.5 * delta)

	match state:
		State.PATROL:
			if _move_to(patrol_target, Config.ENEMY_WALK_SPEED, delta):
				patrol_target = level.random_patrol_point(player.global_position.z)
			if _sees:
				_set_state(State.ALERT)
		State.ALERT:
			_stop()
			_face(last_known, delta)
			if _state_time >= Config.ENEMY_REACTION_TIME:
				_take_cover()
		State.COVER:
			var p: Vector3 = cover["pos"]
			if _move_to(p, Config.ENEMY_RUN_SPEED, delta) or _state_time > 12.0:
				_relocate = randf_range(Config.ENEMY_RELOCATE_TIME.x, Config.ENEMY_RELOCATE_TIME.y)
				_set_state(State.SHOOT)
		State.SHOOT:
			_stop()
			_face(last_known, delta)
			if _sees:
				_fire_logic(delta)
			_relocate -= delta
			if _relocate <= 0.0:
				_take_cover()
			elif _lost_time > Config.ENEMY_LOSE_SIGHT_TIME:
				_set_state(State.HUNT)
		State.HUNT:
			if _move_to(last_known, Config.ENEMY_RUN_SPEED, delta):
				last_known = player.global_position  # «почув» гравця
			if _sees:
				_set_state(State.ALERT)
				_state_time = Config.ENEMY_REACTION_TIME * 0.5
	move_and_slide()


func _take_cover() -> void:
	cover = level.find_cover(global_position, player.global_position, self)
	if cover.is_empty():
		_relocate = randf_range(Config.ENEMY_RELOCATE_TIME.x, Config.ENEMY_RELOCATE_TIME.y)
		_set_state(State.SHOOT)
	else:
		_set_state(State.COVER)


## Повертає true, коли ціль досягнуто.
func _move_to(target: Vector3, speed: float, delta: float) -> bool:
	if NavigationServer3D.map_get_iteration_id(agent.get_navigation_map()) == 0:
		return false
	if target.distance_to(_nav_target) > 0.5:
		_nav_target = target
		agent.target_position = target
	var flat := global_position - target
	flat.y = 0
	if agent.is_navigation_finished() or flat.length() < 0.8:
		_stop()
		return true
	# захист від застрягання: якщо майже не рухаємось — зсуваємось убік і перебудовуємо шлях
	if get_real_velocity().length() < speed * 0.2:
		_stuck_t += delta
		if _stuck_t > 1.2:
			_stuck_t = 0.0
			_nav_target = Vector3(INF, INF, INF)
			if state == State.PATROL:
				patrol_target = level.random_patrol_point(player.global_position.z)
			elif state == State.COVER:
				_relocate = randf_range(Config.ENEMY_RELOCATE_TIME.x, Config.ENEMY_RELOCATE_TIME.y)
				_set_state(State.SHOOT)  # б'ємо з поточної позиції
			var back := (global_basis.z + global_basis.x * randf_range(-1.0, 1.0)).normalized() * 2.0
			velocity.x = back.x
			velocity.z = back.z
			return false
	else:
		_stuck_t = 0.0
	var next := agent.get_next_path_position()
	var dir := next - global_position
	dir.y = 0
	if dir.length() > 0.01:
		dir = dir.normalized()
		velocity.x = dir.x * speed
		velocity.z = dir.z * speed
		_face(global_position + dir, delta)
	return false


func _stop() -> void:
	velocity.x = 0
	velocity.z = 0


func _face(target: Vector3, delta: float) -> void:
	var d := target - global_position
	if Vector2(d.x, d.z).length() < 0.01:
		return
	rotation.y = lerp_angle(rotation.y, atan2(-d.x, -d.z), 1.0 - exp(-8.0 * delta))


func _eye() -> Vector3:
	return global_position + Vector3(0, 1.55, 0)


func _can_see_player() -> bool:
	if player == null or player.dead:
		return false
	var target := player.head.global_position
	var to := target - _eye()
	var dist := to.length()
	var view := Config.ENEMY_VIEW_DISTANCE * (0.7 if player.crouching else 1.0)
	if dist > view:
		return false
	if state == State.PATROL:
		var fwd := -global_basis.z
		if rad_to_deg(fwd.angle_to(to)) > Config.ENEMY_VIEW_HALF_ANGLE and dist > 6.0:
			return false
	var q := PhysicsRayQueryParameters3D.create(_eye(), target, 1 | 2, [get_rid()])
	var hit := get_world_3d().direct_space_state.intersect_ray(q)
	return not hit.is_empty() and hit["collider"] == player


func _fire_logic(delta: float) -> void:
	_fire_timer -= delta
	if _fire_timer > 0.0:
		return
	if _burst_left <= 0:
		_burst_left = Config.ENEMY_BURST
	_shoot()
	_burst_left -= 1
	if _burst_left > 0:
		_fire_timer = Config.ENEMY_BURST_DELAY
	else:
		_fire_timer = Config.ENEMY_FIRE_INTERVAL * randf_range(0.8, 1.5)


func _shoot() -> void:
	var from := global_position + global_basis * Vector3(0.18, 1.3, -0.7)
	var target := player.global_position + Vector3(0, randf_range(0.8, player.head.position.y), 0)
	var dir := (target - from).normalized()
	var perp := dir.cross(Vector3.UP).normalized()
	var up := perp.cross(dir)
	var a := randf() * TAU
	var r := tan(deg_to_rad(spread)) * sqrt(randf())
	dir = (dir + (perp * cos(a) + up * sin(a)) * r).normalized()
	var q := PhysicsRayQueryParameters3D.create(from, from + dir * 120.0, 1 | 2, [get_rid()])
	var hit := get_world_3d().direct_space_state.intersect_ray(q)
	_muzzle.light_energy = 2.0
	_muzzle.visible = true
	Sfx.play("enemy_shot", from, -3.0)
	if hit.is_empty():
		return
	if hit["collider"] == player:
		player.apply_damage(Config.ENEMY_DAMAGE, global_position)
	else:
		var hp: Vector3 = hit["position"]
		if hp.distance_to(player.global_position) < 20.0:
			var n: Vector3 = hit["normal"]
			var col: Object = hit["collider"]
			var surf: String = col.get_meta("surface", "concrete") if col else "concrete"
			Fx.impact(hp, n, "sparks" if surf == "metal" else "dust")


func apply_damage(amount: float, _from_pos: Vector3, _headshot := false) -> bool:
	if state == State.DEAD:
		return false
	health -= amount
	if health <= 0.0:
		_die()
		return true
	last_known = player.global_position
	spread = minf(Config.ENEMY_BASE_SPREAD, spread + 1.5)  # придушення
	if state == State.PATROL or state == State.HUNT:
		_set_state(State.ALERT)
	return false


func _die() -> void:
	_set_state(State.DEAD)
	remove_from_group("enemies")
	remove_from_group("damageable")
	level.release_cover(self)
	collision_layer = 0
	collision_mask = 1
	velocity = Vector3.ZERO
	_muzzle.light_energy = 0.0
	var tw := create_tween()
	tw.tween_property(_visual, "rotation:x", deg_to_rad(85), 0.5).set_trans(Tween.TRANS_QUAD).set_ease(Tween.EASE_IN)
	tw.parallel().tween_property(_visual, "position:y", 0.15, 0.5)
	tw.tween_interval(6.0)
	tw.tween_callback(queue_free)
	died.emit(self)
