extends Node3D
## Ігрова сцена: збирає рівень, гравця, HUD, хвилі; пауза та екрани завершення.

var level: Level
var player: Player
var hud: HUD
var waves: WaveManager
var _pause_layer: CanvasLayer
var _over := false


func _ready() -> void:
	get_tree().paused = false
	level = Level.new()
	add_child(level)
	player = Player.new()
	player.position = level.player_start
	add_child(player)
	level.follow_target = player
	hud = HUD.new()
	add_child(hud)
	hud.bind(player)
	waves = WaveManager.new()
	add_child(waves)
	waves.setup(level, player)
	waves.wave_started.connect(_on_wave_started)
	waves.wave_cleared.connect(_on_wave_cleared)
	waves.kills_changed.connect(hud.set_kills)
	waves.all_cleared.connect(func(): _end(true))
	player.died.connect(func(): get_tree().create_timer(1.5).timeout.connect(func(): _end(false)))
	player.pause_requested.connect(_pause)
	hud.set_kills(0)
	hud.set_wave(0, waves.total_waves())
	hud.message("Тримайте вулицю!", 3.0)
	Sfx.start_ambience()
	waves.start()
	if not OS.has_feature("mobile") and DisplayServer.get_name() != "headless":
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED


func _on_wave_started(i: int, total: int) -> void:
	hud.set_wave(i, total)
	hud.message("Хвиля %d" % i)


func _on_wave_cleared(i: int) -> void:
	if i < waves.total_waves():
		player.rifle.add_ammo(Config.AMMO_PER_WAVE)
		player.add_grenades(Config.GRENADES_PER_WAVE)
		hud.message("Хвилю відбито! +набої, +граната")


func _pause() -> void:
	if _over or get_tree().paused:
		return
	player.release_inputs()
	hud.touch.release_all()
	get_tree().paused = true
	Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
	_pause_layer = CanvasLayer.new()
	_pause_layer.layer = 10
	_pause_layer.process_mode = Node.PROCESS_MODE_ALWAYS
	add_child(_pause_layer)
	var holder := Control.new()
	holder.set_anchors_preset(Control.PRESET_FULL_RECT)
	holder.theme = UiKit.make_theme()
	_pause_layer.add_child(holder)
	var vb := UiKit.overlay(holder, 0.6)
	vb.add_child(UiKit.label("ПАУЗА", 52, Color(0.98, 0.85, 0.35)))
	vb.add_child(UiKit.button("Продовжити", _resume))
	vb.add_child(UiKit.button("Налаштування", func():
		vb.visible = false
		var center := CenterContainer.new()
		center.set_anchors_preset(Control.PRESET_FULL_RECT)
		holder.add_child(center)
		var sp := SettingsPanel.new()
		center.add_child(sp)
		sp.closed.connect(func():
			center.queue_free()
			vb.visible = true)))
	vb.add_child(UiKit.button("Головне меню", _to_menu))


func _resume() -> void:
	if _pause_layer:
		_pause_layer.queue_free()
		_pause_layer = null
	get_tree().paused = false
	if not OS.has_feature("mobile"):
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED


func _to_menu() -> void:
	Sfx.stop_ambience()
	get_tree().paused = false
	get_tree().change_scene_to_file("res://scenes/MainMenu.tscn")


func _end(victory: bool) -> void:
	if _over:
		return
	_over = true
	waves.active = false
	player.release_inputs()
	hud.touch.release_all()
	get_tree().paused = true
	Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
	var layer := CanvasLayer.new()
	layer.layer = 10
	layer.process_mode = Node.PROCESS_MODE_ALWAYS
	add_child(layer)
	var holder := Control.new()
	holder.set_anchors_preset(Control.PRESET_FULL_RECT)
	holder.theme = UiKit.make_theme()
	layer.add_child(holder)
	var vb := UiKit.overlay(holder, 0.7)
	if victory:
		vb.add_child(UiKit.label("ПЕРЕМОГА", 64, Color(0.98, 0.85, 0.35)))
		vb.add_child(UiKit.label("Вулицю втримано. Місто стоїть.", 28))
	else:
		vb.add_child(UiKit.label("ПОРАЗКА", 64, Color(0.95, 0.3, 0.25)))
		vb.add_child(UiKit.label("Позицію втрачено. Спробуйте ще раз.", 28))
	vb.add_child(UiKit.label("Знищено ворогів: %d   ·   Хвиль: %d / %d" % [waves.kills, waves.wave + (1 if victory else 0), waves.total_waves()], 26))
	vb.add_child(UiKit.button("Ще раз", func():
		get_tree().paused = false
		get_tree().reload_current_scene()))
	vb.add_child(UiKit.button("Головне меню", _to_menu))
