class_name Mats
extends RefCounted
## Спільні PBR-матеріали з процедурними текстурами (textures/, див. tools/gen_textures.py).
## Тріпланарне накладання — текстури лягають рівно на будь-які бокси без UV.

# метрів на один повтор текстури, шорсткість, металічність
const SPECS := {
	"concrete": [3.5, 0.92, 0.0],
	"brick": [2.4, 0.9, 0.0],
	"plaster": [3.0, 0.95, 0.0],
	"asphalt": [4.0, 0.88, 0.0],
	"pavement": [2.0, 0.9, 0.0],
	"sandbag": [0.9, 0.98, 0.0],
	"rust": [2.0, 0.8, 0.15],
	"metal": [0.6, 0.55, 0.25],
	"camo": [0.7, 0.95, 0.0],
}

# матеріал -> набір PBR-текстур з assets/textures (Poly Haven, CC0) і розмір повтору (м)
const ASSET_SETS := {
	"concrete": ["concrete", 3.0],
	"brick": ["brick", 2.0],
	"plaster": ["plaster", 2.5],
	"asphalt": ["asphalt", 4.0],
	"pavement": ["pavement", 2.0],
	"sandbag": ["fabric", 0.8],
	"rust": ["rust", 1.5],
}

static var _cache := {}


## world=true — текстура в світових координатах (статична геометрія),
## false — в координатах об'єкта (персонажі, зброя).
static func get_mat(name: String, tint := Color.WHITE, world := true) -> StandardMaterial3D:
	var key := "%s|%s|%s" % [name, tint.to_html(), world]
	if _cache.has(key):
		return _cache[key]
	var m := StandardMaterial3D.new()
	m.albedo_color = tint
	var set_dir := ""
	if ASSET_SETS.has(name):
		var set_name: String = ASSET_SETS[name][0]
		set_dir = "res://assets/textures/%s/%s" % [set_name, set_name]
		if not ResourceLoader.exists(set_dir + "_albedo.jpg"):
			set_dir = ""
	if set_dir != "":
		var sp: Array = SPECS[name]
		m.albedo_texture = load(set_dir + "_albedo.jpg")
		m.normal_texture = load(set_dir + "_normal.jpg")
		m.normal_enabled = Settings.quality >= 1
		m.roughness_texture = load(set_dir + "_roughness.jpg")
		m.roughness_texture_channel = BaseMaterial3D.TEXTURE_CHANNEL_GREEN
		m.roughness = 1.0
		if ResourceLoader.exists(set_dir + "_ao.jpg"):
			m.ao_enabled = true
			m.ao_texture = load(set_dir + "_ao.jpg")
			m.ao_texture_channel = BaseMaterial3D.TEXTURE_CHANNEL_GREEN
			m.ao_light_affect = 0.6
		m.metallic = sp[2]
		m.uv1_triplanar = true
		m.uv1_world_triplanar = world
		m.uv1_triplanar_sharpness = 6.0
		m.uv1_scale = Vector3.ONE / float(ASSET_SETS[name][1])
	elif SPECS.has(name):
		var s: Array = SPECS[name]
		m.albedo_texture = load("res://textures/%s_albedo.jpg" % name)
		m.normal_texture = load("res://textures/%s_normal.png" % name)
		m.normal_enabled = Settings.quality >= 1
		m.normal_scale = 1.0
		m.uv1_triplanar = true
		m.uv1_world_triplanar = world
		m.uv1_triplanar_sharpness = 6.0
		m.uv1_scale = Vector3.ONE / float(s[0])
		m.roughness = s[1]
		m.metallic = s[2]
	else:
		m.roughness = 0.9
	_cache[key] = m
	return m


static func apply_quality() -> void:
	for m: StandardMaterial3D in _cache.values():
		if m.normal_texture:
			m.normal_enabled = Settings.quality >= 1
