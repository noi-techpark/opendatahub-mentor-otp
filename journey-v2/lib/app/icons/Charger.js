// SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
//
// SPDX-License-Identifier: AGPL-3.0-or-later

import React from "react";

const Charger = ({ title, width = 26.62, height = 23.293, iconColor = '#000', ...props }) => (
  <svg xmlns="http://www.w3.org/2000/svg" width={width} height={height} {...props} viewBox="0 0 26.62 23.293">
    <path d="M33.806,41.966V36.974h-.674a3.49,3.49,0,0,1-2.912-5.382l.832-1.289-6.4.017,2.2-4.991h7.408L36.414,22H25.749a1.664,1.664,0,0,0-1.51,1.007L21.8,28.655H19.664a1.664,1.664,0,0,0,0,3.328h.711l-.711,1.664v4.991A3.328,3.328,0,0,0,21.328,41.5v2.126a1.664,1.664,0,0,0,1.664,1.664h1.664a1.664,1.664,0,0,0,1.664-1.664V41.966ZM24.655,35.31a1.664,1.664,0,1,1-1.664,1.664A1.664,1.664,0,0,1,24.655,35.31Zm19.808-.965L37.629,44.906a.72.72,0,0,1-1.327-.391V34.479h-3.17a.994.994,0,0,1-.832-1.531l6.834-10.561a.72.72,0,0,1,1.327.391V32.815h3.165a.994.994,0,0,1,.836,1.531Z" transform="translate(-18 -22)" fill={iconColor}/>
  </svg>
);

export default Charger;


