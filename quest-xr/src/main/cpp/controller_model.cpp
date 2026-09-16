#include "controller_model.h"

#include <android/log.h>

#include <cmath>
#include <vector>

#define TAG "QuestXr"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace questxr {
namespace {

// Model space: meters, X right, Y up out of the button face, Z towards the player (the grips).
constexpr float CM = 0.01f;

enum Part {
    PART_BODY,
    PART_DPAD_R, PART_DPAD_L, PART_DPAD_D, PART_DPAD_U,
    PART_START, PART_Z, PART_B, PART_A,
    PART_C_R, PART_C_L, PART_C_D, PART_C_U,
    PART_R, PART_L,
    PART_STICK, PART_STICK_WELL, PART_DPAD_CENTER,
    PART_COUNT
};

// N64 button index (AbstractController) -> part
const Part kButtonParts[] = {
    PART_DPAD_R, PART_DPAD_L, PART_DPAD_D, PART_DPAD_U, PART_START, PART_Z, PART_B, PART_A,
    PART_C_R, PART_C_L, PART_C_D, PART_C_U, PART_R, PART_L,
};

const float kColors[PART_COUNT][3] = {
    {0.30f, 0.30f, 0.33f},                                                      // body
    {0.16f, 0.16f, 0.18f}, {0.16f, 0.16f, 0.18f}, {0.16f, 0.16f, 0.18f}, {0.16f, 0.16f, 0.18f},  // d-pad
    {0.85f, 0.12f, 0.12f},                                                      // start
    {0.45f, 0.45f, 0.48f},                                                      // Z
    {0.15f, 0.62f, 0.28f},                                                      // B
    {0.15f, 0.32f, 0.90f},                                                      // A
    {0.95f, 0.78f, 0.10f}, {0.95f, 0.78f, 0.10f}, {0.95f, 0.78f, 0.10f}, {0.95f, 0.78f, 0.10f},  // C
    {0.45f, 0.45f, 0.48f}, {0.45f, 0.45f, 0.48f},                               // R, L
    {0.55f, 0.55f, 0.58f},                                                      // stick
    {0.12f, 0.12f, 0.14f},                                                      // stick well
    {0.16f, 0.16f, 0.18f},                                                      // d-pad center
};

struct Vertex {
    float px, py, pz;
    float nx, ny, nz;
    float part;
};

class MeshBuilder {
public:
    std::vector<Vertex> vertices;

    // Optional transform applied to generated geometry: rotation around Y by yaw at (pivotX, pivotZ)
    float yaw = 0.0f;
    float pivotX = 0.0f;
    float pivotZ = 0.0f;

    void triangle(const XrVector3f& a, const XrVector3f& b, const XrVector3f& c, const XrVector3f& n, Part part) {
        add(a, n, part);
        add(b, n, part);
        add(c, n, part);
    }

    void quad(const XrVector3f& a, const XrVector3f& b, const XrVector3f& c, const XrVector3f& d,
              const XrVector3f& na, const XrVector3f& nb, const XrVector3f& nc, const XrVector3f& nd, Part part) {
        add(a, na, part); add(b, nb, part); add(c, nc, part);
        add(a, na, part); add(c, nc, part); add(d, nd, part);
    }

    // Convex prism from a top-view outline (x, z), between y0 and y1
    void prism(const std::vector<std::pair<float, float>>& outline, float y0, float y1, Part part) {
        float cx = 0.0f, cz = 0.0f;
        for (const auto& p : outline) {
            cx += p.first;
            cz += p.second;
        }
        cx /= static_cast<float>(outline.size());
        cz /= static_cast<float>(outline.size());

        const size_t count = outline.size();
        for (size_t i = 0; i < count; ++i) {
            const auto& p0 = outline[i];
            const auto& p1 = outline[(i + 1) % count];
            triangle({cx, y1, cz}, {p0.first, y1, p0.second}, {p1.first, y1, p1.second}, {0, 1, 0}, part);
            triangle({cx, y0, cz}, {p1.first, y0, p1.second}, {p0.first, y0, p0.second}, {0, -1, 0}, part);

            const XrVector3f n0 = outward(p0.first - cx, p0.second - cz);
            const XrVector3f n1 = outward(p1.first - cx, p1.second - cz);
            quad({p0.first, y0, p0.second}, {p1.first, y0, p1.second}, {p1.first, y1, p1.second}, {p0.first, y1, p0.second},
                 n0, n1, n1, n0, part);
        }
    }

    void roundedRect(float x0, float x1, float z0, float z1, float radius, float y0, float y1, Part part) {
        std::vector<std::pair<float, float>> outline;
        const int segments = 8;
        // Corner centers counterclockwise in (x, z), each sweeping a quarter circle
        const float corners[4][3] = {
            {x1 - radius, z1 - radius, 0.0f},
            {x0 + radius, z1 - radius, 0.5f * M_PI},
            {x0 + radius, z0 + radius, M_PI},
            {x1 - radius, z0 + radius, 1.5f * M_PI},
        };
        for (int c = 0; c < 4; ++c) {
            for (int s = 0; s <= segments; ++s) {
                const float angle = corners[c][2] + static_cast<float>(s) / segments * 0.5f * M_PI;
                outline.emplace_back(corners[c][0] + radius * std::cos(angle), corners[c][1] + radius * std::sin(angle));
            }
        }
        prism(outline, y0, y1, part);
    }

    void cylinder(float cx, float cz, float radius, float y0, float y1, Part part) {
        std::vector<std::pair<float, float>> outline;
        const int segments = 24;
        for (int s = 0; s < segments; ++s) {
            const float angle = -static_cast<float>(s) / segments * 2.0f * M_PI;
            outline.emplace_back(cx + radius * std::cos(angle), cz + radius * std::sin(angle));
        }
        prism(outline, y0, y1, part);
    }

    void box(float x0, float x1, float z0, float z1, float y0, float y1, Part part) {
        prism({{x1, z1}, {x1, z0}, {x0, z0}, {x0, z1}}, y0, y1, part);
    }

private:
    static XrVector3f outward(float dx, float dz) {
        const float length = std::sqrt(dx * dx + dz * dz);
        return length > 0.0f ? XrVector3f{dx / length, 0.0f, dz / length} : XrVector3f{0.0f, 0.0f, 1.0f};
    }

    void add(const XrVector3f& p, const XrVector3f& n, Part part) {
        const float c = std::cos(yaw), s = std::sin(yaw);
        const float x = p.x - pivotX, z = p.z - pivotZ;
        vertices.push_back({pivotX + c * x + s * z, p.y, pivotZ - s * x + c * z,
                            c * n.x + s * n.z, n.y, -s * n.x + c * n.z,
                            static_cast<float>(part)});
    }
};

std::vector<Vertex> buildN64Controller() {
    MeshBuilder m;
    const float face = 1.2f * CM;  // top of the body

    // Body: wide upper part and three grips
    m.roundedRect(-8.0f * CM, 8.0f * CM, -4.8f * CM, 2.6f * CM, 3.0f * CM, -1.2f * CM, face, PART_BODY);
    m.roundedRect(-2.2f * CM, 2.2f * CM, 0.0f, 10.0f * CM, 2.2f * CM, -2.4f * CM, 0.9f * CM, PART_BODY);

    m.yaw = 0.2f; m.pivotX = -5.5f * CM; m.pivotZ = 1.0f * CM;
    m.roundedRect(-7.6f * CM, -3.4f * CM, -1.0f * CM, 9.0f * CM, 2.1f * CM, -1.6f * CM, 1.0f * CM, PART_BODY);
    m.yaw = -0.2f; m.pivotX = 5.5f * CM;
    m.roundedRect(3.4f * CM, 7.6f * CM, -1.0f * CM, 9.0f * CM, 2.1f * CM, -1.6f * CM, 1.0f * CM, PART_BODY);
    m.yaw = 0.0f; m.pivotX = 0.0f; m.pivotZ = 0.0f;

    // Shoulder buttons on the far edge, Z under the center grip
    m.roundedRect(-7.2f * CM, -2.8f * CM, -5.6f * CM, -4.2f * CM, 0.6f * CM, -0.4f * CM, 0.9f * CM, PART_L);
    m.roundedRect(2.8f * CM, 7.2f * CM, -5.6f * CM, -4.2f * CM, 0.6f * CM, -0.4f * CM, 0.9f * CM, PART_R);
    m.roundedRect(-1.1f * CM, 1.1f * CM, 1.6f * CM, 4.4f * CM, 0.5f * CM, -3.2f * CM, -2.3f * CM, PART_Z);

    // D-pad
    const float dx = -5.2f * CM, dz = -1.2f * CM, arm = 0.55f * CM, reach = 1.7f * CM;
    const float dTop = face + 0.5f * CM;
    m.box(dx - arm, dx + arm, dz - reach, dz - arm, face, dTop, PART_DPAD_U);
    m.box(dx - arm, dx + arm, dz + arm, dz + reach, face, dTop, PART_DPAD_D);
    m.box(dx - reach, dx - arm, dz - arm, dz + arm, face, dTop, PART_DPAD_L);
    m.box(dx + arm, dx + reach, dz - arm, dz + arm, face, dTop, PART_DPAD_R);
    m.box(dx - arm, dx + arm, dz - arm, dz + arm, face, dTop, PART_DPAD_CENTER);

    // Start
    m.cylinder(0.0f, -2.2f * CM, 0.55f * CM, face, face + 0.35f * CM, PART_START);

    // Analog stick on the center grip, the knob part gets tilted in the shader
    m.cylinder(0.0f, 3.4f * CM, 1.7f * CM, 0.9f * CM, 1.05f * CM, PART_STICK_WELL);
    m.cylinder(0.0f, 3.4f * CM, 0.45f * CM, 1.05f * CM, 2.1f * CM, PART_STICK);
    m.cylinder(0.0f, 3.4f * CM, 1.0f * CM, 2.1f * CM, 2.5f * CM, PART_STICK);

    // A, B
    m.cylinder(4.4f * CM, 0.4f * CM, 0.78f * CM, face, face + 0.45f * CM, PART_A);
    m.cylinder(3.0f * CM, -1.1f * CM, 0.78f * CM, face, face + 0.45f * CM, PART_B);

    // C buttons
    const float cx = 6.0f * CM, cz = -1.8f * CM, spread = 1.3f * CM, cr = 0.55f * CM;
    m.cylinder(cx, cz - spread, cr, face, face + 0.4f * CM, PART_C_U);
    m.cylinder(cx, cz + spread, cr, face, face + 0.4f * CM, PART_C_D);
    m.cylinder(cx - spread, cz, cr, face, face + 0.4f * CM, PART_C_L);
    m.cylinder(cx + spread, cz, cr, face, face + 0.4f * CM, PART_C_R);

    return m.vertices;
}

GLuint compile(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("Controller shader compile failed: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

}  // namespace

bool ControllerModel::init() {
    static const char* kVertex = R"(#version 300 es
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in float aPart;
uniform mat4 uModel;
uniform mat4 uViewProjection;
uniform vec2 uStick;
flat out int vPart;
out vec3 vNormal;
void main() {
    vec3 position = aPosition;
    int part = int(aPart + 0.5);
    if (part == 15) {
        // Tilt the knob: stick up moves it away from the player (-Z)
        float lift = max(position.y - 0.0105, 0.0) / 0.015;
        position.x += uStick.x * 0.009 * lift;
        position.z -= uStick.y * 0.009 * lift;
    }
    vPart = part;
    vNormal = mat3(uModel) * aNormal;
    gl_Position = uViewProjection * uModel * vec4(position, 1.0);
})";
    static const char* kFragment = R"(#version 300 es
precision mediump float;
flat in int vPart;
in vec3 vNormal;
uniform vec3 uColors[18];
uniform float uPressed[18];
uniform float uAlpha;
uniform vec3 uLight;
out vec4 outColor;
void main() {
    vec3 normal = normalize(vNormal);
    vec3 base = uColors[vPart];
    float diffuse = max(dot(normal, uLight), 0.0);
    vec3 color = base * (0.35 + 0.65 * diffuse);
    float pressed = uPressed[vPart];
    color = mix(color, min(base * 1.5 + 0.45, vec3(1.0)), pressed);
    outColor = vec4(color, mix(uAlpha, 1.0, pressed));
})";

    GLuint vertex = compile(GL_VERTEX_SHADER, kVertex);
    GLuint fragment = compile(GL_FRAGMENT_SHADER, kFragment);
    if (vertex == 0 || fragment == 0) {
        return false;
    }
    program_ = glCreateProgram();
    glAttachShader(program_, vertex);
    glAttachShader(program_, fragment);
    glLinkProgram(program_);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = 0;
    glGetProgramiv(program_, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGE("Controller program link failed");
        return false;
    }

    modelLocation_ = glGetUniformLocation(program_, "uModel");
    viewProjectionLocation_ = glGetUniformLocation(program_, "uViewProjection");
    stickLocation_ = glGetUniformLocation(program_, "uStick");
    colorsLocation_ = glGetUniformLocation(program_, "uColors");
    pressedLocation_ = glGetUniformLocation(program_, "uPressed");
    alphaLocation_ = glGetUniformLocation(program_, "uAlpha");
    lightLocation_ = glGetUniformLocation(program_, "uLight");

    const std::vector<Vertex> vertices = buildN64Controller();
    vertexCount_ = static_cast<GLsizei>(vertices.size());

    glGenVertexArrays(1, &vao_);
    glBindVertexArray(vao_);
    glGenBuffers(1, &vbo_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(vertices.size() * sizeof(Vertex)), vertices.data(),
                 GL_STATIC_DRAW);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void*>(0));
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void*>(3 * sizeof(float)));
    glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void*>(6 * sizeof(float)));
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glEnableVertexAttribArray(2);
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    return true;
}

void ControllerModel::destroy() {
    if (vbo_ != 0) glDeleteBuffers(1, &vbo_);
    if (vao_ != 0) glDeleteVertexArrays(1, &vao_);
    if (program_ != 0) glDeleteProgram(program_);
    vbo_ = vao_ = program_ = 0;
}

void ControllerModel::draw(const float* viewProjection, const float* model, const N64State& state, float alpha) const {
    float colors[PART_COUNT * 3];
    float pressed[PART_COUNT] = {};
    for (int part = 0; part < PART_COUNT; ++part) {
        colors[part * 3] = kColors[part][0];
        colors[part * 3 + 1] = kColors[part][1];
        colors[part * 3 + 2] = kColors[part][2];
    }
    for (int button = 0; button < static_cast<int>(sizeof(kButtonParts) / sizeof(kButtonParts[0])); ++button) {
        if (state.buttons & (1 << button)) {
            pressed[kButtonParts[button]] = 1.0f;
        }
    }
    if (state.axisX != 0.0f || state.axisY != 0.0f) {
        pressed[PART_STICK] = 0.6f;
    }

    // Light from above, slightly from the player's side
    const float light[3] = {0.25f, 0.9f, 0.36f};

    glUseProgram(program_);
    glUniformMatrix4fv(modelLocation_, 1, GL_FALSE, model);
    glUniformMatrix4fv(viewProjectionLocation_, 1, GL_FALSE, viewProjection);
    glUniform2f(stickLocation_, state.axisX, state.axisY);
    glUniform3fv(colorsLocation_, PART_COUNT, colors);
    glUniform1fv(pressedLocation_, PART_COUNT, pressed);
    glUniform1f(alphaLocation_, alpha);
    glUniform3fv(lightLocation_, 1, light);
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLES, 0, vertexCount_);
    glBindVertexArray(0);
}

void matrixFromBasis(const XrVector3f& x, const XrVector3f& y, const XrVector3f& z, const XrVector3f& position,
                     float scale, float* out) {
    out[0] = x.x * scale; out[1] = x.y * scale; out[2] = x.z * scale; out[3] = 0.0f;
    out[4] = y.x * scale; out[5] = y.y * scale; out[6] = y.z * scale; out[7] = 0.0f;
    out[8] = z.x * scale; out[9] = z.y * scale; out[10] = z.z * scale; out[11] = 0.0f;
    out[12] = position.x; out[13] = position.y; out[14] = position.z; out[15] = 1.0f;
}

XrVector3f rotateVector(const XrQuaternionf& q, const XrVector3f& v) {
    // v' = v + 2w(q x v) + 2 q x (q x v)
    const XrVector3f u = {q.x, q.y, q.z};
    const XrVector3f t = {2.0f * (u.y * v.z - u.z * v.y), 2.0f * (u.z * v.x - u.x * v.z), 2.0f * (u.x * v.y - u.y * v.x)};
    return {v.x + q.w * t.x + (u.y * t.z - u.z * t.y),
            v.y + q.w * t.y + (u.z * t.x - u.x * t.z),
            v.z + q.w * t.z + (u.x * t.y - u.y * t.x)};
}

void matrixView(const XrPosef& eye, float* out) {
    const XrVector3f x = rotateVector(eye.orientation, {1, 0, 0});
    const XrVector3f y = rotateVector(eye.orientation, {0, 1, 0});
    const XrVector3f z = rotateVector(eye.orientation, {0, 0, 1});
    const XrVector3f& p = eye.position;
    // Inverse of a rigid transform: transpose rotation, rotate negated translation
    out[0] = x.x; out[4] = x.y; out[8] = x.z;  out[12] = -(x.x * p.x + x.y * p.y + x.z * p.z);
    out[1] = y.x; out[5] = y.y; out[9] = y.z;  out[13] = -(y.x * p.x + y.y * p.y + y.z * p.z);
    out[2] = z.x; out[6] = z.y; out[10] = z.z; out[14] = -(z.x * p.x + z.y * p.y + z.z * p.z);
    out[3] = 0.0f; out[7] = 0.0f; out[11] = 0.0f; out[15] = 1.0f;
}

void matrixProjection(const XrFovf& fov, float nearZ, float farZ, float* out) {
    const float left = std::tan(fov.angleLeft);
    const float right = std::tan(fov.angleRight);
    const float up = std::tan(fov.angleUp);
    const float down = std::tan(fov.angleDown);
    const float width = right - left;
    const float height = up - down;

    out[0] = 2.0f / width; out[4] = 0.0f;          out[8] = (right + left) / width;             out[12] = 0.0f;
    out[1] = 0.0f;         out[5] = 2.0f / height; out[9] = (up + down) / height;               out[13] = 0.0f;
    out[2] = 0.0f;         out[6] = 0.0f;          out[10] = -(farZ + nearZ) / (farZ - nearZ);  out[14] = -2.0f * farZ * nearZ / (farZ - nearZ);
    out[3] = 0.0f;         out[7] = 0.0f;          out[11] = -1.0f;                             out[15] = 0.0f;
}

void matrixMultiply(const float* a, const float* b, float* out) {
    for (int column = 0; column < 4; ++column) {
        for (int row = 0; row < 4; ++row) {
            float sum = 0.0f;
            for (int k = 0; k < 4; ++k) {
                sum += a[k * 4 + row] * b[column * 4 + k];
            }
            out[column * 4 + row] = sum;
        }
    }
}

}  // namespace questxr
