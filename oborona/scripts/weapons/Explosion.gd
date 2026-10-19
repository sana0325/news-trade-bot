class_name Explosion
extends RefCounted
## Спільна логіка вибуху (граната, дрон).

## Повертає кількість знищених цілей.
static func detonate(ctx: Node3D, pos: Vector3, radius: float, damage: float) -> int:
	Fx.explosion(pos)
	Sfx.play("explosion", pos, 6.0, 0.1)
	var space := ctx.get_world_3d().direct_space_state
	var kills := 0
	for n in ctx.get_tree().get_nodes_in_group("damageable"):
		var t := n as Node3D
		if t == null:
			continue
		var tp := t.global_position + Vector3.UP
		var d := tp.distance_to(pos)
		if t is Player:
			(t as Player).shake(clampf(1.0 - d / 30.0, 0.0, 1.0) * 3.0)
			if d < 25.0:
				Settings.vibrate(Config.VIBRATE_EXPLOSION)
		if d > radius:
			continue
		var q := PhysicsRayQueryParameters3D.create(pos + Vector3.UP * 0.3, tp, 1)
		if not space.intersect_ray(q).is_empty():
			continue  # перекрито укриттям
		var dmg := damage * clampf(1.0 - d / radius, 0.25, 1.0)
		if t is Player:
			dmg *= Config.SELF_EXPLOSION_MULT
		if t.call("apply_damage", dmg, pos, false):
			kills += 1
	return kills
