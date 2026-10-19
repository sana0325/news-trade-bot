class_name SoldierModel
extends Node3D
## Процедурна модель піхотинця з анатомічними пропорціями (зріст ~1.8 м) і процедурною анімацією:
## крок ногами (IK), руки тримають гвинтівку (IK), підйом зброї для стрільби.
## Обличчя закрите балаклавою й окулярами — без конкретної символіки.

const UPPER_ARM := 0.29
const FOREARM := 0.27
const THIGH := 0.45
const SHIN := 0.44
const HIP_Y := 1.0

var aim := 0.0           # 0 — зброя опущена, 1 — прицілювання
var _phase := 0.0
var _speed := 0.0

var _uniform: Material
var _gear: Material
var _dark: Material
var _skin: Material
var _pelvis: Node3D
var _torso: Node3D
var _gun: Node3D
var _arms := []   # [плече, лікоть(сегмент), кисть]
var _legs := []   # [стегно, гомілка, черевик]


func _ready() -> void:
	_uniform = Mats.get_mat("camo", Color(0.78, 0.8, 0.74), false)
	_gear = Mats.get_mat("camo", Color(0.62, 0.64, 0.56), false)
	_dark = Mats.get_mat("metal", Color(0.9, 0.9, 0.85), false)
	var skin := StandardMaterial3D.new()
	skin.albedo_color = Color(0.62, 0.48, 0.4)
	skin.roughness = 0.65
	_skin = skin
	_build()
	animate(0.0, 0.0, false)


func _mesh(parent: Node3D, mesh: Mesh, mat: Material, pos := Vector3.ZERO, rot := Vector3.ZERO, scl := Vector3.ONE) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = mat
	mi.position = pos
	mi.rotation_degrees = rot
	mi.scale = scl
	parent.add_child(mi)
	return mi


func _sphere(r: float, hemi := false) -> SphereMesh:
	var s := SphereMesh.new()
	s.radius = r
	s.height = r if hemi else r * 2.0
	s.is_hemisphere = hemi
	s.radial_segments = 12
	s.rings = 6
	return s


func _box(size: Vector3) -> BoxMesh:
	var b := BoxMesh.new()
	b.size = size
	return b


## Сегмент кінцівки вздовж -Z від точки кріплення (для look_at).
func _limb(length: float, r0: float, r1: float, mat: Material) -> Node3D:
	var pivot := Node3D.new()
	add_child(pivot)
	var c := CylinderMesh.new()
	c.top_radius = r0
	c.bottom_radius = r1
	c.height = length
	c.radial_segments = 10
	c.rings = 1
	_mesh(pivot, c, mat, Vector3(0, 0, -length * 0.5), Vector3(-90, 0, 0))
	_mesh(pivot, _sphere(r0 * 1.02), mat)   # суглоб
	return pivot


func _build() -> void:
	_pelvis = Node3D.new()
	_pelvis.position.y = HIP_Y
	add_child(_pelvis)
	_mesh(_pelvis, _sphere(0.17), _uniform, Vector3(0, 0.02, 0), Vector3.ZERO, Vector3(1.0, 0.7, 0.72))
	_mesh(_pelvis, _box(Vector3(0.36, 0.05, 0.24)), _dark, Vector3(0, 0.07, 0))  # пояс

	_torso = Node3D.new()
	_torso.position.y = 0.08
	_pelvis.add_child(_torso)
	_mesh(_torso, _sphere(0.2), _uniform, Vector3(0, 0.25, 0), Vector3.ZERO, Vector3(1.0, 1.35, 0.68))   # тулуб
	_mesh(_torso, _box(Vector3(0.36, 0.36, 0.25)), _gear, Vector3(0, 0.3, 0))                         # плитоноска
	for x in [-0.11, 0.0, 0.11]:
		_mesh(_torso, _box(Vector3(0.09, 0.11, 0.06)), _gear, Vector3(x, 0.2, -0.15))                   # підсумки
	_mesh(_torso, _box(Vector3(0.3, 0.38, 0.14)), _gear, Vector3(0, 0.32, 0.19))                      # рюкзак
	_mesh(_torso, _box(Vector3(0.12, 0.08, 0.04)), _dark, Vector3(0.12, 0.42, -0.14))                 # рація
	for sx: float in [-1.0, 1.0]:
		_mesh(_torso, _sphere(0.075), _uniform, Vector3(sx * 0.2, 0.48, 0), Vector3.ZERO, Vector3(1, 0.8, 1))  # плечі
	# голова
	var head := Node3D.new()
	head.position = Vector3(0, 0.66, 0)
	_torso.add_child(head)
	_mesh(head, CylinderMesh.new(), _uniform, Vector3(0, -0.07, 0), Vector3.ZERO, Vector3(0.11, 0.06, 0.11))   # шия
	_mesh(head, _sphere(0.1), _dark, Vector3(0, 0.02, 0), Vector3.ZERO, Vector3(0.92, 1.12, 1.0))             # балаклава
	_mesh(head, _sphere(0.035), _skin, Vector3(0, 0.03, -0.085), Vector3.ZERO, Vector3(1.6, 0.6, 0.5))       # перенісся
	_mesh(head, _box(Vector3(0.17, 0.045, 0.04)), _dark, Vector3(0, 0.04, -0.085))                           # окуляри
	_mesh(head, _sphere(0.125, true), _gear, Vector3(0, 0.05, 0.005), Vector3.ZERO, Vector3(1, 0.95, 1.08))  # шолом
	_mesh(head, _box(Vector3(0.25, 0.02, 0.27)), _gear, Vector3(0, 0.055, 0.005))                            # край шолома
	for sx: float in [-1.0, 1.0]:
		var ear := CylinderMesh.new()
		ear.top_radius = 0.04
		ear.bottom_radius = 0.04
		ear.height = 0.035
		_mesh(head, ear, _dark, Vector3(sx * 0.105, 0.01, 0), Vector3(0, 0, 90))   # навушники
	# зброя
	_gun = RifleMesh.build(_dark, Mats.get_mat("metal", Color(0.7, 0.7, 0.65), false), Mats.get_mat("metal", Color(1.6, 1.5, 1.15), false))
	add_child(_gun)
	# руки
	for sx: float in [-1.0, 1.0]:
		var up := _limb(UPPER_ARM, 0.068, 0.055, _uniform)
		var fo := _limb(FOREARM, 0.055, 0.042, _uniform)
		var hand := Node3D.new()
		add_child(hand)
		_mesh(hand, _sphere(0.045), _dark, Vector3.ZERO, Vector3.ZERO, Vector3(0.8, 1.0, 1.25))
		_arms.append([up, fo, hand, sx])
	# ноги
	for sx: float in [-1.0, 1.0]:
		var th := _limb(THIGH, 0.1, 0.072, _uniform)
		var sh := _limb(SHIN, 0.068, 0.054, _uniform)
		var boot := Node3D.new()
		add_child(boot)
		_mesh(boot, _box(Vector3(0.12, 0.12, 0.29)), _dark, Vector3(0, -0.02, -0.05))
		_legs.append([th, sh, boot, sx])


static func _ik(a: Vector3, t: Vector3, l1: float, l2: float, pole: Vector3) -> Vector3:
	var d := t - a
	var dist := clampf(d.length(), 0.02, l1 + l2 - 0.002)
	var dir := d.normalized()
	var x := (l1 * l1 - l2 * l2 + dist * dist) / (2.0 * dist)
	var h := sqrt(maxf(l1 * l1 - x * x, 0.0))
	var p := (pole - dir * pole.dot(dir)).normalized()
	return a + dir * x + p * h


func _orient(n: Node3D, from: Vector3, to: Vector3, up := Vector3.UP) -> void:
	var d := to - from
	if d.length() < 0.001:
		return
	var u := up if absf(d.normalized().dot(up)) < 0.95 else Vector3.FORWARD
	n.transform = Transform3D(Basis.looking_at(d, u), from)


## speed — горизонтальна швидкість (м/с), aiming — чи стріляє.
func animate(delta: float, speed: float, aiming: bool) -> void:
	_speed = lerpf(_speed, speed, 1.0 - exp(-8.0 * delta)) if delta > 0.0 else speed
	aim = move_toward(aim, 1.0 if aiming else 0.0, delta * 4.0)
	var k := clampf(_speed / 4.5, 0.0, 1.0)
	_phase = fmod(_phase + delta * (2.0 + _speed * 1.6), TAU)
	var bob := absf(sin(_phase)) * 0.035 * k
	_pelvis.position.y = HIP_Y - 0.02 - bob + 0.03 * (1.0 - k)
	_torso.rotation.x = -0.12 * k - 0.05 * aim
	# ноги
	var stride := 0.32 * k
	for leg in _legs:
		var sx: float = leg[3]
		var ph := _phase + (0.0 if sx < 0 else PI)
		var hip := Vector3(sx * 0.1, _pelvis.position.y - 0.04, 0)
		var lift := maxf(0.0, cos(ph)) * 0.14 * k
		var foot := Vector3(sx * 0.11, 0.1 + lift, -sin(ph) * stride + 0.02)
		var knee := _ik(hip, foot, THIGH, SHIN, Vector3(0, 0, -1))
		_orient(leg[0], hip, knee, Vector3.FORWARD)
		_orient(leg[1], knee, foot, Vector3.FORWARD)
		(leg[2] as Node3D).position = foot
		(leg[2] as Node3D).rotation = Vector3(0, 0, 0)
	# зброя: опущена (готовність) ↔ прицілювання
	var chest_y := _pelvis.position.y + 0.08
	var low := Transform3D(Basis.from_euler(Vector3(-0.45, 0.35, 0)), Vector3(0.05, chest_y + 0.22, -0.28))
	var up := Transform3D(Basis.from_euler(Vector3(0.0, 0.03, 0)), Vector3(0.1, chest_y + 0.45, -0.32))
	var gt := low.interpolate_with(up, aim)
	_gun.transform = gt
	# руки
	for arm in _arms:
		var sx: float = arm[3]
		var shoulder := Vector3(sx * 0.2, chest_y + 0.47, 0.0)
		var hand_t := gt * (RifleMesh.GRIP_RIGHT if sx > 0 else RifleMesh.GRIP_LEFT)
		var elbow := _ik(shoulder, hand_t, UPPER_ARM, FOREARM, Vector3(sx * 0.6, -1.0, 0.2))
		_orient(arm[0], shoulder, elbow)
		_orient(arm[1], elbow, hand_t)
		(arm[2] as Node3D).position = hand_t


## Точка дула у світових координатах (для спалаху пострілу).
func muzzle_global() -> Vector3:
	return _gun.global_transform * RifleMesh.MUZZLE
