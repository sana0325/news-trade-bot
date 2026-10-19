class_name Grenade
extends RigidBody3D
## Осколкова граната з таймером.

var fuse: float = Config.GRENADE_FUSE


func _ready() -> void:
	collision_layer = 8
	collision_mask = 1
	mass = 0.4
	continuous_cd = true
	linear_damp = 0.1
	angular_damp = 1.0
	var pm := PhysicsMaterial.new()
	pm.bounce = 0.3
	pm.friction = 0.8
	physics_material_override = pm
	var cs := CollisionShape3D.new()
	var sh := SphereShape3D.new()
	sh.radius = 0.08
	cs.shape = sh
	add_child(cs)
	var mi := MeshInstance3D.new()
	var sm := SphereMesh.new()
	sm.radius = 0.08
	sm.height = 0.16
	sm.radial_segments = 10
	sm.rings = 6
	mi.mesh = sm
	var m := StandardMaterial3D.new()
	m.albedo_color = Color(0.25, 0.3, 0.2)
	mi.material_override = m
	add_child(mi)


func _physics_process(delta: float) -> void:
	fuse -= delta
	if fuse <= 0.0:
		set_physics_process(false)
		Explosion.detonate(self, global_position, Config.GRENADE_RADIUS, Config.GRENADE_DAMAGE)
		queue_free()
