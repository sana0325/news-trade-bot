class_name RifleMesh
extends RefCounted
## Процедурна модель штурмової гвинтівки (загальний сучасний вигляд, без конкретної марки).
## Профілі деталей видавлюються з 2D-контурів. Вісь стволу — -Z, початок координат — біля руків'я.

## Точки хвата в координатах моделі.
const GRIP_RIGHT := Vector3(0.0, -0.075, 0.065)
const GRIP_LEFT := Vector3(0.0, -0.035, -0.3)
const STOCK_END := Vector3(0.0, -0.02, 0.4)
const MUZZLE := Vector3(0.0, 0.012, -0.69)


static func build(steel: Material, dark: Material, poly: Material) -> Node3D:
	var root := Node3D.new()
	var st_steel := SurfaceTool.new()
	var st_dark := SurfaceTool.new()
	var st_poly := SurfaceTool.new()
	for st in [st_steel, st_dark, st_poly]:
		st.begin(Mesh.PRIMITIVE_TRIANGLES)
	# ствольна коробка
	_extrude(st_steel, [Vector2(0.13, 0.05), Vector2(-0.2, 0.05), Vector2(-0.2, -0.02), Vector2(-0.05, -0.035),
		Vector2(0.06, -0.035), Vector2(0.13, -0.02)], 0.052)
	# планка Пікатінні з зубцями
	_box(st_dark, Vector3(0, 0.058, -0.06), Vector3(0.026, 0.012, 0.36))
	for i in 16:
		_box(st_dark, Vector3(0, 0.066, 0.1 - i * 0.022), Vector3(0.03, 0.006, 0.01))
	# цівка з вентиляційними прорізами
	_extrude(st_poly, [Vector2(-0.2, 0.048), Vector2(-0.45, 0.044), Vector2(-0.46, 0.03), Vector2(-0.46, -0.03),
		Vector2(-0.44, -0.045), Vector2(-0.2, -0.045)], 0.062)
	for i in 5:
		for sx: float in [-1.0, 1.0]:
			_box(st_dark, Vector3(sx * 0.0315, 0.005, -0.25 - i * 0.04), Vector3(0.002, 0.018, 0.026))
	# ствол, газовий блок, дульне гальмо
	_cyl(st_steel, Vector3(0, 0.012, -0.46), Vector3(0, 0.012, -0.66), 0.0105, 10)
	_cyl(st_dark, Vector3(0, 0.012, -0.47), Vector3(0, 0.012, -0.5), 0.017, 10)
	_cyl(st_dark, Vector3(0, 0.012, -0.64), Vector3(0, 0.012, -0.71), 0.016, 10)
	for i in 3:
		_box(st_steel, Vector3(0, 0.026, -0.655 - i * 0.017), Vector3(0.034, 0.006, 0.006))
	# вигнутий магазин
	_extrude(st_dark, [Vector2(-0.02, -0.03), Vector2(-0.105, -0.03), Vector2(-0.135, -0.11), Vector2(-0.17, -0.2),
		Vector2(-0.18, -0.24), Vector2(-0.115, -0.255), Vector2(-0.1, -0.21), Vector2(-0.07, -0.13), Vector2(-0.035, -0.07)], 0.03)
	_box(st_dark, Vector3(0, -0.035, -0.06), Vector3(0.046, 0.025, 0.1))   # шахта магазину
	# пістолетне руків'я
	_extrude(st_poly, [Vector2(0.03, -0.03), Vector2(0.08, -0.03), Vector2(0.115, -0.15), Vector2(0.105, -0.165),
		Vector2(0.07, -0.165), Vector2(0.045, -0.06)], 0.034)
	# спускова скоба і гачок
	_extrude(st_steel, [Vector2(-0.01, -0.035), Vector2(0.04, -0.035), Vector2(0.04, -0.08), Vector2(-0.005, -0.08),
		Vector2(-0.01, -0.07), Vector2(0.03, -0.07), Vector2(0.03, -0.045), Vector2(-0.01, -0.045)], 0.012)
	_box(st_steel, Vector3(0, -0.055, 0.015), Vector3(0.006, 0.025, 0.006))
	# телескопічний приклад
	_cyl(st_steel, Vector3(0, 0.02, 0.13), Vector3(0, 0.02, 0.3), 0.014, 10)
	_extrude(st_poly, [Vector2(0.25, 0.045), Vector2(0.39, 0.035), Vector2(0.405, 0.01), Vector2(0.405, -0.1),
		Vector2(0.37, -0.105), Vector2(0.33, -0.04), Vector2(0.25, -0.01)], 0.045)
	_box(st_dark, Vector3(0, -0.035, 0.405), Vector3(0.046, 0.14, 0.012))   # гумовий затильник
	# голографічний приціл: корпус з батареєю та рамка-«вікно»
	_box(st_dark, Vector3(0, 0.078, 0.01), Vector3(0.034, 0.014, 0.1))       # кріплення
	_box(st_dark, Vector3(0, 0.1, 0.035), Vector3(0.042, 0.03, 0.05))        # корпус
	_box(st_dark, Vector3(0.026, 0.1, 0.035), Vector3(0.01, 0.022, 0.04))    # кнопки
	for sx: float in [-1.0, 1.0]:
		_box(st_dark, Vector3(sx * 0.022, 0.125, -0.02), Vector3(0.006, 0.05, 0.065))   # бічні стінки
	_box(st_dark, Vector3(0, 0.152, -0.02), Vector3(0.05, 0.006, 0.065))      # дах
	_box(st_dark, Vector3(0, 0.1, -0.02), Vector3(0.05, 0.008, 0.065))        # основа вікна
	# рукоятка зведення, мушка
	_box(st_steel, Vector3(0.033, 0.03, 0.02), Vector3(0.015, 0.012, 0.025))
	_box(st_dark, Vector3(0, 0.072, -0.43), Vector3(0.008, 0.035, 0.012))
	for pair in [[st_steel, steel], [st_dark, dark], [st_poly, poly]]:
		var st: SurfaceTool = pair[0]
		var mi := MeshInstance3D.new()
		mi.mesh = st.commit()
		mi.material_override = pair[1]
		root.add_child(mi)
	return root


# ---------- Геометрія ----------

## Трикутник з правильною (за годинниковою для Godot) орієнтацією відносно зовнішньої нормалі.
static func _tri(st: SurfaceTool, a: Vector3, b: Vector3, c: Vector3, outward: Vector3) -> void:
	var n := (b - a).cross(c - a)
	if n.dot(outward) > 0.0:
		var t := b
		b = c
		c = t
	st.set_normal(outward.normalized())
	st.add_vertex(a)
	st.add_vertex(b)
	st.add_vertex(c)


## Видавлює контур (z, y) на товщину по осі X.
static func _extrude(st: SurfaceTool, pts: Array, thickness: float) -> void:
	var poly := PackedVector2Array(pts)
	var hx := thickness * 0.5
	var idx := Geometry2D.triangulate_polygon(poly)
	for i in range(0, idx.size(), 3):
		var p := [poly[idx[i]], poly[idx[i + 1]], poly[idx[i + 2]]]
		for sx: float in [-1.0, 1.0]:
			_tri(st, Vector3(sx * hx, p[0].y, p[0].x), Vector3(sx * hx, p[1].y, p[1].x), Vector3(sx * hx, p[2].y, p[2].x), Vector3(sx, 0, 0))
	for i in poly.size():
		var a := poly[i]
		var b := poly[(i + 1) % poly.size()]
		var e := b - a
		var n2 := Vector2(-e.y, e.x).normalized()
		if Geometry2D.is_point_in_polygon((a + b) * 0.5 + n2 * 0.001, poly):
			n2 = -n2
		var out := Vector3(0, n2.y, n2.x)
		var a0 := Vector3(-hx, a.y, a.x)
		var a1 := Vector3(hx, a.y, a.x)
		var b0 := Vector3(-hx, b.y, b.x)
		var b1 := Vector3(hx, b.y, b.x)
		_tri(st, a0, a1, b1, out)
		_tri(st, a0, b1, b0, out)


static func _box(st: SurfaceTool, c: Vector3, s: Vector3) -> void:
	var h := s * 0.5
	for axis in 3:
		for sg: float in [-1.0, 1.0]:
			var n := Vector3.ZERO
			n[axis] = sg
			var u := Vector3.ZERO
			u[(axis + 1) % 3] = 1.0
			var v := Vector3.ZERO
			v[(axis + 2) % 3] = 1.0
			var fc := c + n * h
			var uu := u * h
			var vv := v * h
			_tri(st, fc - uu - vv, fc + uu - vv, fc + uu + vv, n)
			_tri(st, fc - uu - vv, fc + uu + vv, fc - uu + vv, n)


static func _cyl(st: SurfaceTool, a: Vector3, b: Vector3, r: float, seg: int) -> void:
	var axis := (b - a).normalized()
	var u := axis.cross(Vector3.UP if absf(axis.y) < 0.9 else Vector3.RIGHT).normalized()
	var v := axis.cross(u)
	for i in seg:
		var t0 := TAU * i / seg
		var t1 := TAU * (i + 1) / seg
		var d0 := u * cos(t0) + v * sin(t0)
		var d1 := u * cos(t1) + v * sin(t1)
		var dm := (d0 + d1).normalized()
		_tri(st, a + d0 * r, b + d0 * r, b + d1 * r, dm)
		_tri(st, a + d0 * r, b + d1 * r, a + d1 * r, dm)
		_tri(st, a, a + d0 * r, a + d1 * r, -axis)
		_tri(st, b, b + d1 * r, b + d0 * r, axis)
