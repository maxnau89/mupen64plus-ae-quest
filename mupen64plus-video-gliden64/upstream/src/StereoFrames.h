#ifndef STEREO_FRAMES_H
#define STEREO_FRAMES_H

#include <map>
#include <memory>
#include <set>
#include "Types.h"

struct FrameBuffer;

/**
 * Experimental stereoscopic 3D. The display list is walked once per eye, both walks drawing into the
 * same N64 frame buffer, so the left eye has to be put aside before the right one overwrites it.
 * What renderBuffer() then shows is the pair side by side.
 */
class StereoFrames
{
public:
	static StereoFrames & get();

	/** Keeps a copy of the buffer the first walk drew. */
	void captureLeftEye(FrameBuffer * _pBuffer);

	/** True during the first walk of a stereo frame. Every color image that walk leaves is kept,
	 *  not only the last one: games that draw through intermediate buffers end on one of those,
	 *  and the image that is finally shown would otherwise have no left eye. */
	void setCapturing(bool _capturing) { m_capturing = _capturing; }
	bool isCapturing() const { return m_capturing; }

	/** The kept copy belonging to this N64 frame buffer, or null if it was never captured. */
	FrameBuffer * leftEye(const FrameBuffer * _pBuffer) const;


	void destroy();

private:
	StereoFrames() = default;
	StereoFrames(const StereoFrames &) = delete;

	std::unique_ptr<FrameBuffer> _createBuffer(const FrameBuffer * _pMainBuffer);

	// A frame may touch several color images (for example a scene and an overlay). Keeping one
	// global copy made the last image captured for the left eye get paired with an unrelated main
	// image at VI time.
	std::map<u32, std::unique_ptr<FrameBuffer>> m_leftEyes;
	// Addresses whose copy is valid. A copy stays until its buffer is drawn again: with double
	// buffering the image on screen is the one the previous display list drew, and games running at
	// 30 fps show every image twice, so dropping copies once shown left every frame without a pair.
	std::set<u32> m_captured;
	bool m_capturing = false;
};

#endif // STEREO_FRAMES_H
