// SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
//
// SPDX-License-Identifier: AGPL-3.0-or-later

import * as MarkerIcons from '../../icons/map'
import React from 'react'
import ReactDOMServer from 'react-dom/server'

// Marker border colours from the Figma guidelines (Guidelines > Mappa).
// Layers pick an image named `<Icon>-<state>`, e.g. 'StopBus-green'.
const MARKER_STATE_COLORS = {
  amber: '#ffb217',
  green: '#5ad65a',
  grey: '#e3e4e7',
  red: '#d12953'
}

// Rasterised at 2x with pixelRatio 2, so `icon-size: 1` shows the SVG at its
// Figma size and stays sharp on retina screens.
const PIXEL_RATIO = 2

// Rasterised images are kept so they can be put back after the map style is
// replaced: setStyle drops every image, and on slower devices the style can
// finish loading after the first images were added.
const rasterised: Record<string, ImageData> = {}
const loading = new Set<string>()
const listening = new WeakSet<any>()

const addRasterised = (mapInstance: any, imageName: string) => {
  if (rasterised[imageName] && !mapInstance.hasImage(imageName)) {
    mapInstance.addImage(imageName, rasterised[imageName], {
      pixelRatio: PIXEL_RATIO
    })
  }
}

// Registers every marker icon in every state on the map. Safe to call from
// several layers: images already present or loading are skipped.
export const addMarkerImages = (mapInstance: any) => {
  if (!listening.has(mapInstance)) {
    listening.add(mapInstance)
    // A layer asked for an image the current style doesn't have: re-add it.
    mapInstance.on('styleimagemissing', (e: { id: string }) =>
      addRasterised(mapInstance, e.id)
    )
  }
  Object.entries(MarkerIcons).forEach(([iconName, Icon]) => {
    Object.entries(MARKER_STATE_COLORS).forEach(([state, color]) => {
      const imageName = `${iconName}-${state}`
      if (rasterised[imageName]) return addRasterised(mapInstance, imageName)
      if (loading.has(imageName)) return
      loading.add(imageName)
      const svg = ReactDOMServer.renderToStaticMarkup(
        <Icon stateColor={color} />
      )
      const img = new Image()
      img.onload = () => {
        const canvas = document.createElement('canvas')
        canvas.width = img.width * PIXEL_RATIO
        canvas.height = img.height * PIXEL_RATIO
        const ctx = canvas.getContext('2d') as CanvasRenderingContext2D
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
        rasterised[imageName] = ctx.getImageData(
          0,
          0,
          canvas.width,
          canvas.height
        )
        addRasterised(mapInstance, imageName)
      }
      img.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
    })
  })
}
