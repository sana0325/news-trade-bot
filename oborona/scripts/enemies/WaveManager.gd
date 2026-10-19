class_name WaveManager
extends Node
## Хвилі ворогів.

signal wave_started(index: int, total: int)
signal wave_cleared(index: int)
signal all_cleared
signal kills_changed(kills: int)

var level: Level
var player: Player
var wave := -1
var kills := 0
var alive := 0
var active := false

var _to_spawn := 0
var _spawn_timer := 0.0
var _between := 0.0


func setup(l: Level, p: Player) -> void:
	level = l
	player = p


func start() -> void:
	active = true
	_between = 3.0


func total_waves() -> int:
	return Config.WAVES.size()


func _process(delta: float) -> void:
	if not active:
		return
	if _between > 0.0:
		_between -= delta
		if _between <= 0.0:
			_next_wave()
		return
	if _to_spawn > 0:
		_spawn_timer -= delta
		if _spawn_timer <= 0.0 and alive < Config.MAX_ALIVE:
			_spawn()
			_spawn_timer = Config.WAVE_SPAWN_INTERVAL
	elif alive == 0:
		wave_cleared.emit(wave + 1)
		if wave + 1 >= total_waves():
			active = false
			all_cleared.emit()
		else:
			_between = Config.WAVE_DELAY


func _next_wave() -> void:
	wave += 1
	_to_spawn = Config.WAVES[wave]
	_spawn_timer = 0.0
	wave_started.emit(wave + 1, total_waves())


func _spawn() -> void:
	var candidates: Array[Vector3] = []
	for sp in level.spawn_points:
		if sp.distance_to(player.global_position) >= Config.SPAWN_MIN_DIST and sp.z < player.global_position.z:
			candidates.append(sp)
	if candidates.is_empty():
		candidates = level.spawn_points.duplicate()
	var e := Enemy.new()
	e.player = player
	e.level = level
	e.position = candidates.pick_random() + Vector3(randf_range(-1, 1), 0, randf_range(-1, 1))
	get_parent().add_child(e)
	e.patrol_target = level.random_patrol_point(player.global_position.z)
	# з другої хвилі частина ворогів одразу шукає гравця
	if wave >= 1 and randf() < 0.4:
		e.last_known = player.global_position
		e.state = Enemy.State.HUNT
	e.died.connect(_on_enemy_died)
	_to_spawn -= 1
	alive += 1


func _on_enemy_died(_e: Enemy) -> void:
	alive -= 1
	kills += 1
	kills_changed.emit(kills)
