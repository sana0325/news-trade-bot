class_name SettingsPanel
extends PanelContainer
## Панель налаштувань: чутливість, розмір кнопок, автоприціл, вібрація, якість.

signal closed


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	var vb := VBoxContainer.new()
	vb.add_theme_constant_override("separation", 12)
	vb.custom_minimum_size = Vector2(620, 0)
	add_child(vb)
	vb.add_child(UiKit.label("НАЛАШТУВАННЯ", 36, Color(0.95, 0.8, 0.3)))
	_slider(vb, "Чутливість огляду", 0.05, 0.6, Settings.look_sensitivity,
		func(v: float): Settings.look_sensitivity = v)
	_slider(vb, "Розмір кнопок", 0.7, 1.5, Settings.button_scale,
		func(v: float): Settings.button_scale = v)
	_check(vb, "Автоприціл-асист", Settings.aim_assist, func(v: bool): Settings.aim_assist = v)
	_check(vb, "Вібрація", Settings.vibration, func(v: bool): Settings.vibration = v)
	var row := HBoxContainer.new()
	var l := Label.new()
	l.text = "Якість графіки"
	l.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(l)
	var ob := OptionButton.new()
	for q in Settings.QUALITY_NAMES:
		ob.add_item(q)
	ob.selected = Settings.quality
	ob.custom_minimum_size = Vector2(240, 56)
	ob.item_selected.connect(func(i: int):
		Settings.quality = i
		Settings.apply_quality()
		Settings.save_settings())
	row.add_child(ob)
	vb.add_child(row)
	vb.add_child(UiKit.button("Назад", _close))


func _slider(parent: Node, text: String, mn: float, mx: float, val: float, setter: Callable) -> void:
	var row := HBoxContainer.new()
	var l := Label.new()
	l.text = text
	l.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(l)
	var value_l := Label.new()
	value_l.custom_minimum_size = Vector2(70, 0)
	value_l.text = "%.2f" % val
	var s := HSlider.new()
	s.min_value = mn
	s.max_value = mx
	s.step = 0.01
	s.value = val
	s.custom_minimum_size = Vector2(260, 48)
	s.value_changed.connect(func(v: float):
		setter.call(v)
		value_l.text = "%.2f" % v
		Settings.changed.emit())
	row.add_child(s)
	row.add_child(value_l)
	parent.add_child(row)


func _check(parent: Node, text: String, val: bool, setter: Callable) -> void:
	var cb := CheckButton.new()
	cb.text = text
	cb.button_pressed = val
	cb.toggled.connect(func(v: bool): setter.call(v))
	parent.add_child(cb)


func _close() -> void:
	Settings.save_settings()
	closed.emit()
	queue_free()
