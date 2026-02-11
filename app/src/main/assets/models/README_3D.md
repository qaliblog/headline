# 3D Face Mask Engine - Model Support

## Supported Formats
- **.glb**: Fully supported. This is the recommended format as it is self-contained.
- **.gltf**: Supported if self-contained. For GLTF with external textures/buffers, use GLB for best results.
- **.obj / .fbx / .ply**: Not natively supported by the Filament mobile engine.

## How to use OBJ/FBX
To use models in these formats, you should convert them to **GLB** before loading them into the app.
Recommended tools for conversion:
- [Blender](https://www.blender.org/) (Export as GLB)
- [gltf-pipeline](https://github.com/CesiumGS/gltf-pipeline) (Convert GLTF/OBJ to GLB)
- [Online converters](https://blackthread.io/gltf-converter/)

## Limitations
- Only one face is tracked for 3D overlay in the current version.
- Models should be low-poly for best performance on mobile devices.
- Transparency in 3D models depends on the material settings in the GLB file.
