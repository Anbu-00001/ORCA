// cmocean — the colormaps oceanography actually uses.
//
// WHY THIS FILE EXISTS, AND WHY IT IS NOT A "NICER PALETTE"
//
// The seabed used to be tinted by a hand-mixed teal->navy ramp under a
// sqrt(). It looked fine. It was also scientifically wrong in a way that
// matters to the only audience that would ever deploy this: the ramp was
// not perceptually uniform, so equal steps in depth did not produce equal
// perceived steps in colour. Non-uniform ramps invent structure that is
// not in the data -- ridges and fronts appear where the colormap happens
// to change fast, and real gradients vanish where it changes slowly.
// That is the documented failure of rainbow/jet colormaps, and it is the
// reason oceanography moved off them.
//
// These are the EXACT 256-stop tables from cmocean (Thyng, Greene,
// Hetland, Zimmerle & DiMarco 2016, "True Colors of Oceanography:
// Guidelines for Effective and Accurate Colormap Selection",
// Oceanography 29(3):9-13, doi:10.5670/oceanog.2016.66). Not an
// approximation of them and not a look inspired by them -- the published
// values, so the perceptual-uniformity claim ORCA makes about this view
// is literally true and independently checkable.
//
// Each map is bound to ONE variable with ONE unit (see CMOCEAN_META).
// A colormap with no stated variable, no range and no units is
// decoration; with them it is a measurement instrument. web/index.html
// renders the bar, its endpoints and its units from this same metadata,
// so the legend cannot drift from what the shader draws.
//
// Sequential maps are for magnitudes that run one way (depth, wave
// height, speed). Diverging maps are for quantities with a meaningful
// zero to be symmetric about (anomalies) -- using a diverging map for a
// pure magnitude fabricates a midpoint that does not exist.
//
// Generated from cmocean's published rgb tables; regenerate with
// scripts/build_colormaps.py.

const CMOCEAN_HEX = {
  deep: "fdfeccfbfdcbf9fccaf7fbc8f5fac7f3fac6f1f9c5eff8c4edf7c3ebf7c1e9f6c0e7f5bfe5f4bee3f4bde1f3bcdff2bbddf2badbf1b9d9f0b8d7efb7d4efb6d2eeb5d0edb4ceecb3ccecb3caebb2c8eab1c6eab0c4e9afc1e8afbfe7aebde7adbbe6acb9e5acb7e5abb5e4aab2e3aab0e2a9aee2a9ace1a8aae0a8a7e0a7a5dfa7a3dea6a1dda69fdda59cdca59adba598daa496daa494d9a492d8a490d7a48dd7a38bd6a389d5a387d4a385d3a383d3a381d2a37fd1a37dd0a37ccfa37acea378cea376cda375cca373cba371caa370c9a36ec8a36dc7a36bc6a36ac5a469c4a467c3a466c2a465c2a464c1a463c0a462bfa461bea460bda45fbca45ebba45dbaa45cb9a45bb8a45ab7a45ab6a459b4a458b3a458b2a457b1a456b0a456afa455aea355ada354aca353aba353aaa352a9a352a8a351a7a351a6a251a5a250a4a250a3a24fa2a24fa1a24fa0a24e9fa14e9ea14d9da14d9ca14d9ba14c9aa04c99a04b98a04b97a04b96a04a959f4a949f4a939f49929f49919e49909e488f9e488e9e488d9d478c9d478b9d478a9d46899d46889c46879c45869c45859c45849b44839b44829b44819b44809b437f9a437e9a437d9a427c9a427b99427a994279994178994177994176984075984074984073984072984071973f70973f6f973f6e973f6d973f6c963e6b963e6a963e69963e68963e67953e66953e65953e64953e63943e62943e61943e60943e5f933e5e933e5c933e5b933e5a923e59923e58923e57913e56913f55903f54903f538f3f528f3f508e404f8d404e8d404d8c404c8b414b8a414a8941498841488741478541468441458341448142438041427e41417d41407b41407a413f78413e76413d75403c73403c71403b70403a6e3f396c3f386b3f38693e37673e36663d35643d35623d34613c335f3c325d3b325c3b315a3a30583a3057392f55392e54382d52382d51372c4f362b4d362a4c352a4a35294934284734284633274432264332254131254030243e30233d2f223b2f223a2e21392d20372d1f362c1f342b1e332b1d322a1c30291c2f281b2d281a2c",
  thermal: "04233304243504253704253905263b05273d05273f052841052943062945062a47062b49072b4b072c4d072c50082d52082e54092e56092f590a2f5b0b305d0c30600c30620d31650e31670f326a10326c12326f13337214337416337717337a19337c1a347f1c34821e34841f348721348a23348c25348f2734912a33932c33952e339730339933339b35339c37339d39339e3c339f3e349f40349f4234a04435a04635a04736a049369f4b379f4d379f4e389e50399e52399d533a9d553b9d563b9c583c9c593d9b5b3d9b5c3e9a5e3f9a5f3f996040996241986341986542976643976743966944966a45956c45956d46946e4794704794714893724893744992754a92764a92784b91794b917a4c917c4d907d4d907e4e90804e8f814f8f83508f84508e85518e87518e88528d89528d8b538d8c538c8e548c8f548c90558b92558b93568b95568a96578a97578a9958899a58899c59899d59889f5a88a05a87a25b87a35b86a55c86a65c86a85d85a95d84ab5d84ac5e83ae5e83af5f82b15f82b26081b46080b56180b7617fb8627eba627ebb627dbd637cbe637bc0647bc1647ac36579c46578c66677c76676c96775ca6774cc6873cd6872ce6971d06970d16a6fd36a6ed46b6dd66c6cd76c6bd86d6ada6e69db6e68dc6f66de7065df7064e07163e17262e37260e4735fe5745ee6755de7765be8775aea7859eb7958ec7956ed7a55ee7b54ee7d53ef7e52f07f50f1804ff2814ef3824df3834cf4854bf5864af58749f68848f68a47f78b46f78c45f88e44f88f43f99143f99242f99341fa9541fa9640fa983ffb993ffb9b3efb9c3efb9e3efb9f3dfba13dfca33dfca43dfca63cfca73cfca93cfcaa3cfcac3cfcae3cfcaf3cfcb13cfbb23dfbb43dfbb63dfbb73dfbb93efbbb3efbbc3efabe3ffabf3ffac140fac340f9c441f9c641f9c842f8c943f8cb43f8cd44f7ce45f7d045f7d246f6d347f6d547f5d748f5d849f4da4af4dc4bf3dd4bf3df4cf2e14df2e24ef1e44ff1e650f0e851efe951efeb52eeed53edee54edf055ecf256ebf457eaf558eaf759e9f95ae8fa5b",
  speed: "fffdcdfefccbfefac9fdf9c7fcf8c5fcf7c2fbf6c0faf4bef9f3bcf9f2baf8f1b8f7f0b6f7eeb4f6edb1f6ecaff5ebadf4eaabf3e9a9f3e7a7f2e6a5f1e5a2f1e4a0f0e39eefe29cefe19aeedf98edde96eddd93ecdc91ebdb8feada8dead98be9d889e8d786e7d684e7d582e6d480e5d37ee4d27be3d079e3cf77e2ce75e1cd73e0cd71dfcc6edecb6cddca6adcc968dbc866dac764d9c661d8c55fd7c45dd6c35bd5c259d4c157d3c155d2c053d1bf51d0be4fcebd4ccdbd4accbc48cbbb46c9ba45c8b943c7b941c5b83fc4b73dc3b73bc1b639c0b537beb436bdb434bbb332bab230b8b22fb7b12db5b02bb4b02ab2af28b1ae27afae25adad23acad22aaac20a9ab1fa7ab1ea5aa1ca4a91ba2a919a0a8189fa8179da7159ba6149aa61398a51296a51095a40f93a30e91a30d8fa20c8ea20b8ca10a8aa00988a008879f08859f07839e07819d06809d067e9c067c9c067a9b06799a06779a067599067399067198067097076e97076c96076a950868950966940965940a63930b61920b5f920c5d910d5c900e5a900f588f0f568e10548e11528d12518c124f8c134d8b144b8a15498a164889164688174488184287194086193f851a3d851b3b841c39831c38831d36821e34811e32801f317f202f7f202d7e212c7d212a7c22287c23277b23257a242479242278252178251f77261e76261c75271b74271a7327187328177228167129157029136f29126e2a116d2a106c2a0f6c2b0f6b2b0e6a2b0d692b0d682b0c672c0c662c0b652c0b642c0b632c0b632c0b622d0b612d0b602d0b5f2d0b5e2d0c5d2d0c5c2d0c5b2d0d5a2d0d592d0e582d0e572d0f562c0f552c10542c10542c10532c11522c11512c12502b124f2b134e2b134d2b144c2a144b2a144a2a15492a154829164729164629164528174428174327174227174127184026183f26183f25183e25193d24193c24193b23193a2319392219382219372119362119352019341f19331f19321e19311e19301d192f1c192e1c192d1b192c1a192c19192b19192a18182917182817182716182615182514172413172313",
  amp: "f1edecf1ecebf0ebe9efe9e8efe8e7eee7e5eee6e4ede5e3ede3e1ece2e0ece1deebe0ddebdfdceadddaeadcd9e9dbd7e9dad6e9d9d4e8d8d3e8d6d2e7d5d0e7d4cfe6d3cde6d2cce6d1cae5cfc9e5cec8e4cdc6e4ccc5e4cbc3e3c9c2e3c8c0e2c7bfe2c6bde2c5bce1c4bbe1c3b9e1c1b8e0c0b6e0bfb5dfbeb3dfbdb2dfbcb0debaafdeb9aedeb8acddb7abddb6a9ddb5a8dcb4a6dcb2a5dcb1a3dbb0a2dbafa1dbae9fdaad9edaac9cd9aa9bd9a999d9a898d8a796d8a695d8a594d7a492d7a291d7a18fd6a08ed69f8cd69e8bd59d89d59c88d59a87d49985d49884d49782d39681d3957fd3947ed2927dd2917bd2907ad18f78d18e77d18d76d08c74d08b73d08971cf8870cf876fcf866dce856cce846acd8369cd8168cd8066cc7f65cc7e64cc7d62cb7c61cb7a60cb795eca785dca775bc9765ac97559c97457c87256c87155c87054c76f52c76e51c66d50c66b4ec66a4dc5694cc5684ac56749c46548c46447c36346c36244c36143c25f42c25e41c15d3fc15c3ec05b3dc0593cc0583bbf573abf5639be5438be5336bd5235bd5134bd4f33bc4e32bc4d31bb4c30bb4a30ba492fba482eb9462db9452cb8442bb8422bb7412ab74029b63f29b53d28b53c27b43b27b43926b33826b23726b23525b13425b03325b03125af3024ae2f24ae2d24ad2c24ac2b24ab2a24aa2824aa2724a92624a82524a72424a62225a52125a42025a31f25a21e25a11d25a01c269f1b269e1a269d19269c18279b17279a16279915279815279714289513289413289312289211289111299010298e10298d10298c0f298b0f29890f29880f29870e29850e29840e29830e29810e29800e297f0e297d0e297c0e297b0e29790e29780e28770e28750e28740e28730e27710e27700e276f0e266d0e266c0f266b0f25690f25680f25670f24650f24640e23630e23610e22600e225f0e215d0e215c0e215b0e205a0e1f580e1f570e1e560e1e540d1d530d1d520d1c510d1c4f0d1b4e0d1a4d0c1a4b0c194a0c19490c18480b17460b17450b16440b16430a15410a14400a143f0a133d09123c0912",
  balance: "181c43191e461a1f491b214c1c224f1d23521e25551f265820275b21295f212a62222b65232d69242e6c252f6f25307326327627337a27347d283681283784293888293a8c293b8f293c93293e97293f9a29409e2942a22843a52745a92647ac2548b0234ab3214cb61f4eb81c50ba1952bc1655bd1357be1059be0d5bbe0c5ebe0a60be0a62be0a64be0b66bd0d68bd0f6abd116cbc136ebc1670bc1972bb1b74bb1e76bb2178bb237aba267bba297dba2b7fba2e81ba3083ba3384ba3686ba3888ba3b89ba3e8bba408dba438fba4690ba4892ba4b94ba4e95ba5197ba5399ba569abb599cbb5c9dbb5f9fbb62a0bb65a2bc68a4bc6ba5bc6ea7bd71a8bd75aabe78abbe7bacbf7eaebf81afc085b1c088b2c18bb4c28eb5c391b7c394b8c498bac59bbbc69ebcc7a1bec8a4bfc9a7c1caaac2cbadc4ccb0c5cdb3c7ceb6c9cfb9cad0bcccd2bfcdd3c1cfd4c4d0d5c7d2d7cad4d8cdd5d9d0d7dad3d9dcd5daddd8dcdedbdee0dee0e1e1e1e3e3e3e4e6e5e6e9e7e7ebe9e9eeeaeaf1ececf1ecebf0eae9efe8e6eee5e3ede3e0ece0deebdedbeadcd8e9d9d5e8d7d2e7d5cfe6d2cde5d0cae5cec7e4cbc4e3c9c1e2c7bee1c4bbe1c2b8e0c0b5dfbdb2dfbbb0deb9adddb6aadcb4a7dcb2a4dbafa1daad9edaab9bd9a998d8a696d8a493d7a290d69f8dd69d8ad59b87d49984d39681d3947fd2927cd18f79d18d76d08b73cf8970cf866ece846bcd8268cd7f65cc7d63cb7b60ca795dca765bc97458c87255c76f53c76d50c66b4dc5684bc46648c36346c36143c25f41c15c3fc05a3cbf573abe5538be5236bd5034bc4d32bb4b30ba482eb9452cb8432bb74029b63d28b43b27b33826b23525b13325af3024ae2e24ac2b24ab2924a92624a72424a52125a31f25a11d259f1b269d19269b17279916279714289413289212289010298d10298b0f29880f29860e29830e29800e297e0e297b0e29780e28760e28730e27700e276d0e266b0f25680f25650f24630e23600e225e0e215b0e20580e1f560e1e530d1d510d1c4e0d1b4b0c19490c18460b17440b16410a143f0a133c0912",
};

export const CMOCEAN_META = {
  deep: { label: "Depth below sea level", unit: "m", kind: "sequential" },
  thermal: { label: "Sea surface temperature", unit: "\u00b0C", kind: "sequential" },
  speed: { label: "Speed (current / wind)", unit: "km/h", kind: "sequential, constant lightness" },
  amp: { label: "Significant wave height", unit: "m", kind: "sequential" },
  balance: { label: "Anomaly about zero", unit: "signed", kind: "diverging" },
};


// Decode once into Float32Array triples the shaders can upload as a
// DataTexture. 256 stops means no interpolation error to explain away.
const _cache = new Map();

export function colormap(name) {
  if (_cache.has(name)) return _cache.get(name);
  const hex = CMOCEAN_HEX[name];
  if (!hex) throw new Error(`unknown cmocean map: ${name}`);
  const rgb = new Uint8Array(256 * 3);
  for (let i = 0; i < 256; i++) {
    rgb[i * 3 + 0] = parseInt(hex.substr(i * 6 + 0, 2), 16);
    rgb[i * 3 + 1] = parseInt(hex.substr(i * 6 + 2, 2), 16);
    rgb[i * 3 + 2] = parseInt(hex.substr(i * 6 + 4, 2), 16);
  }
  _cache.set(name, rgb);
  return rgb;
}

// GLSL source for sampling a map from a 256x1 texture. Sampling a real
// LUT rather than approximating the curve with mix() is what keeps the
// published values published.
export const COLORMAP_GLSL = /* glsl */ `
  uniform sampler2D uColormap;
  vec3 cmocean(float t) {
    return texture2D(uColormap, vec2(clamp(t, 0.0, 1.0), 0.5)).rgb;
  }
`;

// CSS gradient for the on-screen colour bar, so the legend is drawn from
// the same 256 values as the geometry and cannot drift from it.
export function colormapCss(name, stops = 32) {
  const rgb = colormap(name);
  const parts = [];
  for (let s = 0; s < stops; s++) {
    const i = Math.round((s / (stops - 1)) * 255);
    parts.push(`rgb(${rgb[i*3]},${rgb[i*3+1]},${rgb[i*3+2]}) ${((s/(stops-1))*100).toFixed(1)}%`);
  }
  return `linear-gradient(to right, ${parts.join(", ")})`;
}
