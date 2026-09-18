#include <assert.h>

#include "N64.h"
#include "StereoFrames.h"
#include "FrameBuffer.h"
#include "Config.h"

#include <Graphics/Context.h>
#include <Graphics/Parameters.h>

using namespace graphics;

StereoFrames & StereoFrames::get()
{
	static StereoFrames frames;
	return frames;
}

// Mirrors PostProcessor::_createResultBuffer: a buffer shaped like the main one to blit into.
void StereoFrames::_createBuffer(const FrameBuffer * _pMainBuffer)
{
	m_pLeftEye.reset(new FrameBuffer());
	m_pLeftEye->m_width = _pMainBuffer->m_width;
	m_pLeftEye->m_height = _pMainBuffer->m_height;
	m_pLeftEye->m_scale = _pMainBuffer->m_scale;

	const CachedTexture * pMainTexture = _pMainBuffer->m_pTexture;
	CachedTexture * pTexture = m_pLeftEye->m_pTexture;
	pTexture->format = G_IM_FMT_RGBA;
	pTexture->clampS = 1;
	pTexture->clampT = 1;
	pTexture->frameBufferTexture = CachedTexture::fbOneSample;
	pTexture->maskS = 0;
	pTexture->maskT = 0;
	pTexture->mirrorS = 0;
	pTexture->mirrorT = 0;
	pTexture->width = pMainTexture->width;
	pTexture->height = pMainTexture->height;
	pTexture->textureBytes = pTexture->width * pTexture->height * 4;

	Context::InitTextureParams initParams;
	initParams.handle = pTexture->name;
	initParams.width = pTexture->width;
	initParams.height = pTexture->height;
	initParams.internalFormat = gfxContext.convertInternalTextureFormat(u32(internalcolorFormat::RGBA8));
	initParams.format = colorFormat::RGBA;
	initParams.dataType = datatype::UNSIGNED_BYTE;
	gfxContext.init2DTexture(initParams);

	Context::TexParameters setParams;
	setParams.handle = pTexture->name;
	setParams.target = textureTarget::TEXTURE_2D;
	setParams.minFilter = textureParameters::FILTER_LINEAR;
	setParams.magFilter = textureParameters::FILTER_LINEAR;
	gfxContext.setTextureParameters(setParams);

	Context::FrameBufferRenderTarget bufTarget;
	bufTarget.bufferHandle = m_pLeftEye->m_FBO;
	bufTarget.bufferTarget = bufferTarget::DRAW_FRAMEBUFFER;
	bufTarget.attachment = bufferAttachment::COLOR_ATTACHMENT0;
	bufTarget.textureTarget = textureTarget::TEXTURE_2D;
	bufTarget.textureHandle = pTexture->name;
	gfxContext.addFrameBufferRenderTarget(bufTarget);
	assert(!gfxContext.isFramebufferError());
}

void StereoFrames::captureLeftEye(FrameBuffer * _pBuffer)
{
	if (_pBuffer == nullptr || _pBuffer->m_pTexture == nullptr) {
		m_hasLeftEye = false;
		return;
	}

	const CachedTexture * pSource = _pBuffer->m_pTexture;
	if (m_pLeftEye == nullptr ||
		m_pLeftEye->m_pTexture->width != pSource->width ||
		m_pLeftEye->m_pTexture->height != pSource->height)
		_createBuffer(_pBuffer);

	// A multisampled buffer has to be resolved before it can be read
	ObjectHandle readBuffer = _pBuffer->m_FBO;
	if (pSource->frameBufferTexture == CachedTexture::fbMultiSample) {
		_pBuffer->resolveMultisampledTexture(true);
		readBuffer = _pBuffer->m_resolveFBO;
	}

	Context::BlitFramebuffersParams params;
	params.readBuffer = readBuffer;
	params.drawBuffer = m_pLeftEye->m_FBO;
	params.srcX0 = 0;
	params.srcY0 = 0;
	params.srcX1 = pSource->width;
	params.srcY1 = pSource->height;
	params.dstX0 = 0;
	params.dstY0 = 0;
	params.dstX1 = m_pLeftEye->m_pTexture->width;
	params.dstY1 = m_pLeftEye->m_pTexture->height;
	params.mask = blitMask::COLOR_BUFFER;
	params.filter = textureParameters::FILTER_NEAREST;

	m_hasLeftEye = gfxContext.blitFramebuffers(params);

	gfxContext.bindFramebuffer(bufferTarget::DRAW_FRAMEBUFFER, ObjectHandle::defaultFramebuffer);
}

FrameBuffer * StereoFrames::leftEye() const
{
	return m_hasLeftEye ? m_pLeftEye.get() : nullptr;
}

void StereoFrames::reset()
{
	m_hasLeftEye = false;
}

void StereoFrames::destroy()
{
	m_hasLeftEye = false;
	m_pLeftEye.reset();
}
