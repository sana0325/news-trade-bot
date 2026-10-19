class_name TouchControls
extends Control
## Сенсорне керування: динамічний джойстик зліва, свайп-огляд справа, екранні кнопки.

signal pressed(name: String)
signal released(name: String)

const LABELS := {
	"fire": "ВОГОНЬ", "ads": "ПРИЦІЛ", "reload": "ПЕРЕЗАР.", "crouch": "ПРИСІД",
	"jump": "СТРИБОК", "grenade": "ГРАНАТА", "drone": "ДРОН", "pause": "II",
}
# x, y (частка екрана), радіус (пікселі при висоті 720)
const LAYOUT := {
	"fire": [0.875, 0.6, 72.0],
	"ads": [0.73, 0.73, 48.0],
	"jump": [0.955, 0.86, 42.0],
	"crouch": [0.83, 0.9, 40.0],
	"reload": [0.955, 0.38, 40.0],
	"grenade": [0.83, 0.37, 38.0],
	"drone": [0.7, 0.47, 38.0],
	"pause": [0.965, 0.08, 30.0],
}

var move_vector := Vector2.ZERO
var badges := {}      # ім'я -> додатковий текст (кількість гранат тощо)
var cooldowns := {}   # ім'я -> 0..1 (частка перезарядки)
var toggled := {}     # ім'я -> bool (підсвітка перемикачів)
var drone_mode := false

var _look := Vector2.ZERO
var _touches := {}
var _held := {}
var _joy_index := -1
var _joy_center := Vector2.ZERO
var _joy_knob := Vector2.ZERO


func _ready() -> void:
	set_anchors_preset(Control.PRESET_FULL_RECT)
	mouse_filter = Control.MOUSE_FILTER_IGNORE


func take_look_delta() -> Vector2:
	var d := _look
	_look = Vector2.ZERO
	return d


func is_held(n: String) -> bool:
	return _held.get(n, false)


func release_all() -> void:
	for idx in _touches.keys():
		_touch_up(idx)
	_look = Vector2.ZERO


func _scale() -> float:
	return Settings.button_scale * get_viewport_rect().size.y / 720.0


func _btn_pos(n: String) -> Vector2:
	var l: Array = LAYOUT[n]
	var s := get_viewport_rect().size
	return Vector2(s.x * l[0], s.y * l[1])


func _btn_radius(n: String) -> float:
	var l: Array = LAYOUT[n]
	return float(l[2]) * _scale()


func _hit_button(p: Vector2) -> String:
	var best := ""
	var best_d := INF
	for n in LAYOUT:
		var d := p.distance_to(_btn_pos(n))
		if d <= _btn_radius(n) * 1.15 and d < best_d:
			best = n
			best_d = d
	return best


func _input(event: InputEvent) -> void:
	if not is_visible_in_tree():
		return
	if event is InputEventScreenTouch:
		var t := event as InputEventScreenTouch
		if t.pressed:
			_touch_down(t.index, t.position)
		else:
			_touch_up(t.index)
	elif event is InputEventScreenDrag:
		var d := event as InputEventScreenDrag
		_touch_drag(d.index, d.position, d.relative)


func _touch_down(idx: int, pos: Vector2) -> void:
	var b := _hit_button(pos)
	if b != "":
		_touches[idx] = {"kind": "button", "name": b}
		_held[b] = true
		pressed.emit(b)
	elif pos.x < get_viewport_rect().size.x * 0.42 and _joy_index == -1:
		_joy_index = idx
		_joy_center = pos
		_joy_knob = pos
		_touches[idx] = {"kind": "joy"}
	else:
		_touches[idx] = {"kind": "look"}
	queue_redraw()


func _touch_drag(idx: int, pos: Vector2, rel: Vector2) -> void:
	if not _touches.has(idx):
		return
	var t: Dictionary = _touches[idx]
	match t["kind"]:
		"look":
			_look += rel
		"button":
			if t["name"] == "fire":  # огляд під час стрільби
				_look += rel
		"joy":
			var r := Config.JOYSTICK_RADIUS * _scale()
			_joy_knob = _joy_center + (pos - _joy_center).limit_length(r)
			move_vector = (_joy_knob - _joy_center) / r


func _touch_up(idx: int) -> void:
	if not _touches.has(idx):
		return
	var t: Dictionary = _touches[idx]
	_touches.erase(idx)
	if t["kind"] == "button":
		var n: String = t["name"]
		_held[n] = false
		released.emit(n)
	elif t["kind"] == "joy":
		_joy_index = -1
		move_vector = Vector2.ZERO
	queue_redraw()


func _process(_delta: float) -> void:
	queue_redraw()


func _draw() -> void:
	var font := ThemeDB.fallback_font
	var sc := _scale()
	var vs := get_viewport_rect().size
	# джойстик
	var jr := Config.JOYSTICK_RADIUS * sc
	if _joy_index != -1:
		draw_circle(_joy_center, jr, Color(1, 1, 1, 0.12))
		draw_arc(_joy_center, jr, 0, TAU, 48, Color(1, 1, 1, 0.4), 2.0)
		draw_circle(_joy_knob, jr * 0.42, Color(1, 1, 1, 0.45))
	else:
		var hint := Vector2(vs.x * 0.15, vs.y * 0.72)
		draw_arc(hint, jr, 0, TAU, 48, Color(1, 1, 1, 0.18), 2.0)
		draw_circle(hint, jr * 0.42, Color(1, 1, 1, 0.15))
	# кнопки
	for n in LAYOUT:
		if drone_mode and n not in ["fire", "pause"]:
			continue
		var c := _btn_pos(n)
		var r := _btn_radius(n)
		var held: bool = _held.get(n, false)
		var on: bool = toggled.get(n, false)
		var base := Color(0.95, 0.8, 0.25) if on else Color(1, 1, 1)
		draw_circle(c, r, Color(0, 0, 0, 0.35 if not held else 0.55))
		draw_arc(c, r, 0, TAU, 40, Color(base, 0.75 if held else 0.5), 2.5)
		var cd: float = cooldowns.get(n, 0.0)
		if cd > 0.0:
			draw_arc(c, r - 4.0, -PI / 2, -PI / 2 + TAU * cd, 32, Color(1, 0.3, 0.2, 0.8), 5.0)
		var text: String = LABELS[n]
		if drone_mode and n == "fire":
			text = "ПІДРИВ"
		var fs := int(clampf(r * 0.34, 11, 26))
		if n == "pause":
			fs = int(r * 0.7)
		draw_string(font, c + Vector2(-r, fs * 0.35), text, HORIZONTAL_ALIGNMENT_CENTER, r * 2.0, fs, Color(base, 0.9))
		if badges.has(n):
			draw_string(font, c + Vector2(-r, fs * 0.35 + fs * 1.1), str(badges[n]), HORIZONTAL_ALIGNMENT_CENTER, r * 2.0, fs, Color(1, 1, 1, 0.8))
