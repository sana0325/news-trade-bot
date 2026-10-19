class_name Drone
extends CharacterBody3D
## FPV-дрон: керування камерою, підрив по кнопці «Вогонь» або коли вийде час.

signal finished

var time_left: float = Config.DRONE_DURATION
var speed: float = Config.DRONE_SPEED
var strafe := 0.0
var yaw := 0.0
var pitch := -0.15
var cam: Camera3D
var exploded := false
var _buzz: AudioStreamPlayer


func _ready() -> void:
	collision_layer = 0
	collision_mask = 1 | 4
	var cs := CollisionShape3D.new()
	var sh := SphereShape3D.new()
	sh.radius = 0.25
	cs.shape = sh
	add_child(cs)
	var m := StandardMaterial3D.new()
	m.albedo_color = Color(0.1, 0.1, 0.1)
	for r in [45.0, -45.0]:
		var arm := MeshInstance3D.new()
		var bm := BoxMesh.new()
		bm.size = Vector3(0.6, 0.03, 0.05)
		arm.mesh = bm
		arm.material_override = m
		arm.rotation_degrees.y = r
		add_child(arm)
	cam = Camera3D.new()
	cam.fov = 95.0
	cam.near = 0.05
	cam.position = Vector3(0, 0.05, -0.2)
	add_child(cam)
	cam.current = true
	_buzz = AudioStreamPlayer.new()
	_buzz.stream = Sfx.streams["drone"]
	_buzz.volume_db = -8.0
	add_child(_buzz)
	_buzz.play()


func steer(look_px: Vector2) -> void:
	var k := Settings.look_sensitivity * Config.DRONE_STEER_MULT
	yaw -= deg_to_rad(look_px.x * k)
	pitch = clampf(pitch - deg_to_rad(look_px.y * k), -1.3, 0.7)


func set_input(move: Vector2) -> void:
	speed = clampf(Config.DRONE_SPEED - move.y * 9.0, Config.DRONE_SPEED_MIN, Config.DRONE_SPEED_MAX)
	strafe = move.x


func _physics_process(delta: float) -> void:
	if exploded:
		return
	time_left -= delta
	if time_left <= 0.0:
		detonate()
		return
	var b := Basis.from_euler(Vector3(pitch, yaw, 0))
	rotation = Vector3(pitch, yaw, -strafe * 0.35)
	_buzz.pitch_scale = 0.8 + speed / Config.DRONE_SPEED_MAX * 0.6
	var vel := (-b.z * speed + b.x * strafe * 5.0) * delta
	var col := move_and_collide(vel)
	if col:
		detonate()


func detonate() -> void:
	if exploded:
		return
	exploded = true
	Explosion.detonate(self, global_position, Config.DRONE_RADIUS, Config.DRONE_DAMAGE)
	finished.emit()
	queue_free()
