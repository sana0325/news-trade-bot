class_name UiKit
extends RefCounted
## Спільні елементи інтерфейсу.

static func make_theme() -> Theme:
	var t := Theme.new()
	t.default_font_size = 28
	var normal := StyleBoxFlat.new()
	normal.bg_color = Color(0.12, 0.14, 0.12, 0.92)
	normal.border_color = Color(0.85, 0.72, 0.3)
	normal.set_border_width_all(2)
	normal.set_corner_radius_all(6)
	normal.set_content_margin_all(14)
	var hover := normal.duplicate() as StyleBoxFlat
	hover.bg_color = Color(0.2, 0.22, 0.18, 0.95)
	var press := normal.duplicate() as StyleBoxFlat
	press.bg_color = Color(0.45, 0.38, 0.15, 0.95)
	for s in ["normal", "focus"]:
		t.set_stylebox(s, "Button", normal)
	t.set_stylebox("hover", "Button", hover)
	t.set_stylebox("pressed", "Button", press)
	t.set_color("font_color", "Button", Color(0.95, 0.92, 0.85))
	t.set_color("font_color", "Label", Color(0.95, 0.92, 0.85))
	var panel := StyleBoxFlat.new()
	panel.bg_color = Color(0.06, 0.07, 0.07, 0.94)
	panel.border_color = Color(0.85, 0.72, 0.3, 0.6)
	panel.set_border_width_all(2)
	panel.set_corner_radius_all(8)
	panel.set_content_margin_all(28)
	t.set_stylebox("panel", "PanelContainer", panel)
	return t


static func button(text: String, cb: Callable) -> Button:
	var b := Button.new()
	b.text = text
	b.custom_minimum_size = Vector2(380, 70)
	b.pressed.connect(cb)
	return b


static func label(text: String, size := 28, color := Color(0.95, 0.92, 0.85)) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", color)
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	return l


## Повноекранний затемнений шар із центрованою вертикальною колонкою.
static func overlay(parent: Node, dim := 0.6) -> VBoxContainer:
	var bg := ColorRect.new()
	bg.color = Color(0, 0, 0, dim)
	bg.set_anchors_preset(Control.PRESET_FULL_RECT)
	parent.add_child(bg)
	var center := CenterContainer.new()
	center.set_anchors_preset(Control.PRESET_FULL_RECT)
	bg.add_child(center)
	var vb := VBoxContainer.new()
	vb.add_theme_constant_override("separation", 16)
	vb.alignment = BoxContainer.ALIGNMENT_CENTER
	center.add_child(vb)
	return vb
