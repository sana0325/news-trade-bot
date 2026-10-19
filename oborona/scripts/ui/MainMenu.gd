extends Control
## Головне меню.

var _menu: VBoxContainer


func _ready() -> void:
	get_tree().paused = false
	Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
	theme = UiKit.make_theme()
	var bg := ColorRect.new()
	bg.set_anchors_preset(Control.PRESET_FULL_RECT)
	var g := Gradient.new()
	g.set_color(0, Color(0.95, 0.55, 0.35))
	g.set_color(1, Color(0.08, 0.09, 0.14))
	var gt := GradientTexture2D.new()
	gt.gradient = g
	gt.fill_from = Vector2(0.5, 1.0)
	gt.fill_to = Vector2(0.5, 0.0)
	var tr := TextureRect.new()
	tr.texture = gt
	tr.stretch_mode = TextureRect.STRETCH_SCALE
	tr.set_anchors_preset(Control.PRESET_FULL_RECT)
	add_child(tr)
	_skyline()
	_menu = UiKit.overlay(self, 0.25)
	_menu.add_child(UiKit.label("ОБОРОНА МІСТА", 64, Color(0.98, 0.85, 0.35)))
	_menu.add_child(UiKit.label("Світанок. Вулиця має вистояти.", 26))
	var spacer := Control.new()
	spacer.custom_minimum_size = Vector2(0, 24)
	_menu.add_child(spacer)
	_menu.add_child(UiKit.button("Почати оборону", _start))
	_menu.add_child(UiKit.button("Налаштування", _settings))
	_menu.add_child(UiKit.button("Вихід", func(): get_tree().quit()))


func _skyline() -> void:
	# силует зруйнованого міста з прямокутників
	var rng := RandomNumberGenerator.new()
	rng.seed = 7
	var x := 0.0
	while x < 1.0:
		var w := rng.randf_range(0.04, 0.09)
		var h := rng.randf_range(0.12, 0.38)
		var r := ColorRect.new()
		r.color = Color(0.05, 0.05, 0.06)
		r.anchor_left = x
		r.anchor_right = x + w
		r.anchor_top = 1.0 - h
		r.anchor_bottom = 1.0
		add_child(r)
		x += w + rng.randf_range(0.0, 0.01)


func _start() -> void:
	get_tree().change_scene_to_file("res://scenes/Game.tscn")


func _settings() -> void:
	_menu.visible = false
	var center := CenterContainer.new()
	center.set_anchors_preset(Control.PRESET_FULL_RECT)
	add_child(center)
	var p := SettingsPanel.new()
	center.add_child(p)
	p.closed.connect(func():
		center.queue_free()
		_menu.visible = true)
