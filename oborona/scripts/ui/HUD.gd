class_name HUD
extends CanvasLayer
## HUD: здоров'я, набої, приціл, маркер влучання, індикатор напрямку шкоди, хвилі, дрон.

var player: Player
var touch: TouchControls
var root: Control
var overlay: Control

var _ammo: Label
var _wave: Label
var _kills: Label
var _msg: Label
var _drone_label: Label
var _health := 1.0
var _hit_t := 0.0
var _hit_strong := false
var _damage: Array[Dictionary] = []   # {pos, t}
var _flash := 0.0
var _msg_t := 0.0


func _ready() -> void:
	root = Control.new()
	root.set_anchors_preset(Control.PRESET_FULL_RECT)
	root.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(root)
	overlay = Control.new()
	overlay.set_anchors_preset(Control.PRESET_FULL_RECT)
	overlay.mouse_filter = Control.MOUSE_FILTER_IGNORE
	overlay.draw.connect(_draw_overlay)
	root.add_child(overlay)

	_ammo = _corner_label(Control.PRESET_TOP_RIGHT, Vector2(-330, 70), 40, HORIZONTAL_ALIGNMENT_RIGHT)
	_wave = _corner_label(Control.PRESET_CENTER_TOP, Vector2(-200, 12), 26, HORIZONTAL_ALIGNMENT_CENTER)
	_kills = _corner_label(Control.PRESET_CENTER_TOP, Vector2(-200, 46), 22, HORIZONTAL_ALIGNMENT_CENTER)
	_msg = _corner_label(Control.PRESET_CENTER, Vector2(-400, -170), 44, HORIZONTAL_ALIGNMENT_CENTER, 800.0)
	_msg.add_theme_color_override("font_color", Color(0.98, 0.85, 0.35))
	_msg.add_theme_color_override("font_outline_color", Color.BLACK)
	_msg.add_theme_constant_override("outline_size", 8)
	_drone_label = _corner_label(Control.PRESET_CENTER_BOTTOM, Vector2(-400, -110), 30, HORIZONTAL_ALIGNMENT_CENTER, 800.0)
	_drone_label.add_theme_color_override("font_color", Color(0.6, 1.0, 0.6))

	touch = TouchControls.new()
	root.add_child(touch)


func _corner_label(preset: int, offset: Vector2, size: int, align: HorizontalAlignment, width := 400.0) -> Label:
	var l := Label.new()
	l.set_anchors_preset(preset)
	l.position += offset
	l.custom_minimum_size = Vector2(width, 0)
	l.horizontal_alignment = align
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.8))
	l.add_theme_constant_override("outline_size", 5)
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(l)
	return l


func bind(p: Player) -> void:
	player = p
	p.setup_touch(touch)
	p.health_changed.connect(func(v: float, m: float): _health = v / m)
	p.damaged.connect(_on_damaged)
	p.rifle.ammo_changed.connect(_on_ammo)
	p.rifle.hit_confirmed.connect(_on_hit)
	p.grenades_changed.connect(func(n: int): touch.badges["grenade"] = n)
	p.drone_changed.connect(func(a: bool): touch.drone_mode = a)
	touch.badges["grenade"] = p.grenades
	_on_ammo(p.rifle.mag, p.rifle.reserve)


func set_wave(i: int, total: int) -> void:
	_wave.text = "Хвиля %d / %d" % [i, total]


func set_kills(k: int) -> void:
	_kills.text = "Знищено: %d" % k


func message(text: String, dur := 2.5) -> void:
	_msg.text = text
	_msg_t = dur


func _on_ammo(mag: int, reserve: int) -> void:
	_ammo.text = "%d / %d" % [mag, reserve]
	_ammo.add_theme_color_override("font_color", Color(1, 0.35, 0.3) if mag <= 5 else Color(0.95, 0.92, 0.85))


func _on_hit(headshot: bool, killed: bool) -> void:
	_hit_t = 0.25
	_hit_strong = headshot or killed


func _on_damaged(from_pos: Vector3) -> void:
	_damage.append({"pos": from_pos, "t": 1.4})
	_flash = 0.35


func _process(delta: float) -> void:
	_hit_t = maxf(0.0, _hit_t - delta)
	_flash = maxf(0.0, _flash - delta)
	if _msg_t > 0.0:
		_msg_t -= delta
		if _msg_t <= 0.0:
			_msg.text = ""
	for d in _damage:
		d["t"] = float(d["t"]) - delta
	_damage = _damage.filter(func(d: Dictionary): return float(d["t"]) > 0.0)
	if player:
		touch.toggled["ads"] = player.ads
		touch.toggled["crouch"] = player.crouching
		touch.cooldowns["drone"] = clampf(player.drone_cooldown / Config.DRONE_COOLDOWN, 0.0, 1.0)
		touch.badges["reload"] = "..." if player.rifle.reloading else ""
		if player.drone:
			_drone_label.text = "FPV-ДРОН  %.1f с   ·   ВОГОНЬ — підрив" % player.drone.time_left
		else:
			_drone_label.text = ""
	overlay.queue_redraw()


func _draw_overlay() -> void:
	var s := overlay.size
	var c := s * 0.5
	var font := ThemeDB.fallback_font
	# смуга здоров'я
	var hb := Rect2(Vector2(24, 20), Vector2(280, 20))
	overlay.draw_rect(hb, Color(0, 0, 0, 0.5))
	var hc := Color(0.35, 0.85, 0.4).lerp(Color(0.95, 0.25, 0.2), 1.0 - _health)
	overlay.draw_rect(Rect2(hb.position, Vector2(hb.size.x * _health, hb.size.y)), hc)
	overlay.draw_rect(hb, Color(1, 1, 1, 0.5), false, 2.0)
	overlay.draw_string(font, hb.position + Vector2(0, 46), "Здоров'я %d" % int(_health * 100.0),
		HORIZONTAL_ALIGNMENT_LEFT, -1, 20, Color(1, 1, 1, 0.85))
	if player == null:
		return
	if player.drone:
		_draw_drone_frame(s, c)
	else:
		# приціл (розходиться разом із розкидом)
		if not player.ads:
			var gap := 6.0 + player.rifle.current_spread() * 7.0
			var col := Color(1, 1, 1, 0.85)
			for d in [Vector2.RIGHT, Vector2.LEFT, Vector2.UP, Vector2.DOWN]:
				overlay.draw_line(c + d * gap, c + d * (gap + 12.0), col, 2.0)
		overlay.draw_circle(c, 2.0, Color(1, 0.3, 0.25, 0.9))
		if player.grenade_aiming:
			overlay.draw_string(font, c + Vector2(-200, 90), "Відпустіть, щоб кинути", HORIZONTAL_ALIGNMENT_CENTER, 400, 22, Color(1, 0.85, 0.3))
	# маркер влучання
	if _hit_t > 0.0:
		var hcol := Color(1, 0.25, 0.2, _hit_t * 4.0) if _hit_strong else Color(1, 1, 1, _hit_t * 4.0)
		for d in [Vector2(1, 1), Vector2(-1, 1), Vector2(1, -1), Vector2(-1, -1)]:
			overlay.draw_line(c + d * 7.0, c + d * 16.0, hcol, 3.0)
	# індикатори напрямку шкоди
	for d in _damage:
		var dir: Vector3 = (d["pos"] as Vector3) - player.global_position
		var local := player.global_basis.inverse() * dir
		var ang := atan2(local.x, -local.z)
		var v := Vector2(sin(ang), -cos(ang))
		var p := c + v * 150.0
		var perp := Vector2(-v.y, v.x)
		var a := clampf(float(d["t"]), 0.0, 1.0)
		overlay.draw_colored_polygon(PackedVector2Array([p + v * 26.0, p + perp * 22.0, p - perp * 22.0]), Color(1, 0.15, 0.1, 0.8 * a))
	# червоний спалах при шкоді та низькому здоров'ї
	var vign := maxf(_flash * 1.2, (0.35 - _health) * 1.5 if _health < 0.35 else 0.0)
	if vign > 0.0:
		var w := 40.0
		var vc := Color(0.8, 0.05, 0.05, clampf(vign, 0.0, 0.6))
		overlay.draw_rect(Rect2(0, 0, s.x, w), vc)
		overlay.draw_rect(Rect2(0, s.y - w, s.x, w), vc)
		overlay.draw_rect(Rect2(0, 0, w, s.y), vc)
		overlay.draw_rect(Rect2(s.x - w, 0, w, s.y), vc)


func _draw_drone_frame(s: Vector2, c: Vector2) -> void:
	var col := Color(0.6, 1.0, 0.6, 0.8)
	var m := 60.0
	var l := 50.0
	for corner in [Vector2(m, m), Vector2(s.x - m, m), Vector2(m, s.y - m), Vector2(s.x - m, s.y - m)]:
		var sx := 1.0 if corner.x < c.x else -1.0
		var sy := 1.0 if corner.y < c.y else -1.0
		overlay.draw_line(corner, corner + Vector2(l * sx, 0), col, 3.0)
		overlay.draw_line(corner, corner + Vector2(0, l * sy), col, 3.0)
	overlay.draw_arc(c, 24.0, 0, TAU, 32, col, 2.0)
	overlay.draw_line(c - Vector2(40, 0), c - Vector2(28, 0), col, 2.0)
	overlay.draw_line(c + Vector2(40, 0), c + Vector2(28, 0), col, 2.0)
	# легкі «завади» відеосигналу
	for i in 6:
		var y := randf() * s.y
		overlay.draw_line(Vector2(0, y), Vector2(s.x, y), Color(1, 1, 1, 0.04), 2.0)
