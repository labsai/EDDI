import{x as a,bM as n,b0 as e}from"./index-BRNHHvMP.js";/**
 * @license lucide-react v0.577.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const o=[["path",{d:"M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z",key:"p7xjir"}]],d=a("cloud",o),t="/administration/coordinator";async function c(){return e.get(`${t}/status`)}async function i(){return e.get(`${t}/dead-letters`)}async function u(r){return e.post(`${t}/dead-letters/${r}/replay`)}async function l(r){return e.delete(`${t}/dead-letters/${r}`)}async function p(){return e.delete(`${t}/dead-letters`)}function $(){return new n(`${window.location.origin}${t}/stream`,e.getAuthHeader())}export{d as C,i as a,$ as c,l as d,c as g,p,u as r};
