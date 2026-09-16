// 3D N64 controller rendered into the stereo projection layer.
#pragma once

#include <GLES3/gl3.h>
#include <openxr/openxr.h>

#include <cstdint>
#include <vector>

namespace questxr {

// Bit i set = N64 button i pressed, using the button indices of AbstractController.java
struct N64State {
    int buttons = 0;
    float axisX = 0.0f;
    float axisY = 0.0f;
};

// Mesh loaded from assets/quest/n64_controller.bin (see tools/convert_n64_model.py) plus its texture
struct ControllerAsset {
    std::vector<float> vertices;  // 9 floats per vertex: position, normal, uv, part
    std::vector<uint8_t> rgba;
    int32_t textureWidth = 0;
    int32_t textureHeight = 0;

    bool valid() const { return !vertices.empty() && !rgba.empty(); }
};

class ControllerModel {
public:
    // Requires a current GL context. Uses the asset if valid, the procedural model otherwise.
    bool init(const ControllerAsset& asset);
    void destroy();

    // Matrices are column-major. alpha is the opacity of unpressed parts.
    void draw(const float* viewProjection, const float* model, const N64State& state, float alpha) const;

private:
    GLuint program_ = 0;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
    GLuint texture_ = 0;
    GLsizei vertexCount_ = 0;
    bool textured_ = false;
    GLint modelLocation_ = -1;
    GLint viewProjectionLocation_ = -1;
    GLint stickLocation_ = -1;
    GLint colorsLocation_ = -1;
    GLint pressedLocation_ = -1;
    GLint alphaLocation_ = -1;
    GLint lightLocation_ = -1;
    GLint texturedLocation_ = -1;
    GLint stickPivotLocation_ = -1;
    GLint glowPassLocation_ = -1;
};

// Column-major 4x4 matrix helpers
void matrixFromBasis(const XrVector3f& x, const XrVector3f& y, const XrVector3f& z, const XrVector3f& position,
                     float scale, float* out);
void matrixView(const XrPosef& eye, float* out);
void matrixProjection(const XrFovf& fov, float nearZ, float farZ, float* out);
void matrixMultiply(const float* a, const float* b, float* out);

XrVector3f rotateVector(const XrQuaternionf& q, const XrVector3f& v);

}  // namespace questxr
