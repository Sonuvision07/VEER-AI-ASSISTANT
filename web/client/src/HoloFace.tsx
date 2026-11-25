import{useEffect,useRef}from"react";
import*as THREE from"three";
type Props={status:"idle"|"listening"|"thinking"|"speaking"|"error"};
export default function HoloFace({status}:Props){
 const host=useRef<HTMLDivElement>(null);
 useEffect(()=>{
  const root=host.current;if(!root)return;
  let renderer:THREE.WebGLRenderer;
  try{renderer=new THREE.WebGLRenderer({alpha:true,antialias:true,powerPreference:"low-power"})}catch{return}
  renderer.setPixelRatio(Math.min(window.devicePixelRatio||1,1.7));renderer.setSize(root.clientWidth,root.clientHeight);renderer.setClearColor(0x000000,0);root.appendChild(renderer.domElement);
  const scene=new THREE.Scene(),camera=new THREE.PerspectiveCamera(38,root.clientWidth/root.clientHeight,.1,100);camera.position.set(0,0,5.3);
  const group=new THREE.Group();scene.add(group);
  const cyan=new THREE.MeshBasicMaterial({color:0x63eaff,wireframe:true,transparent:true,opacity:.72});
  const head=new THREE.Mesh(new THREE.SphereGeometry(1,28,24),cyan);head.scale.set(.76,1.08,.66);group.add(head);
  const inner=new THREE.Mesh(new THREE.SphereGeometry(1,18,16),new THREE.MeshBasicMaterial({color:0x39bfff,wireframe:true,transparent:true,opacity:.13}));inner.scale.set(.77,1.09,.67);group.add(inner);
  const eyes:THREE.Mesh[]=[];for(const x of[-.31,.31]){const eye=new THREE.Mesh(new THREE.TorusGeometry(.105,.012,5,24),new THREE.MeshBasicMaterial({color:0x9af5ff,transparent:true,opacity:.95}));eye.position.set(x,.2,.57);group.add(eye);eyes.push(eye);const pupil=new THREE.Mesh(new THREE.SphereGeometry(.045,10,8),new THREE.MeshBasicMaterial({color:0x8af4ff}));pupil.position.set(x,.2,.61);group.add(pupil)}
  const nose=new THREE.Mesh(new THREE.ConeGeometry(.07,.28,5),new THREE.MeshBasicMaterial({color:0x56dfff,wireframe:true,transparent:true,opacity:.5}));nose.rotation.x=Math.PI; nose.position.set(0,-.03,.62);group.add(nose);
  const mouth=new THREE.Mesh(new THREE.TorusGeometry(.16,.012,5,28,Math.PI),new THREE.MeshBasicMaterial({color:0x7ceeff,transparent:true,opacity:.85}));mouth.position.set(0,-.39,.58);mouth.rotation.z=Math.PI;group.add(mouth);
  const count=850,positions=new Float32Array(count*3);for(let i=0;i<count;i++){const v=new THREE.Vector3().randomDirection().multiplyScalar(.85+Math.random()*.7);positions.set([v.x,v.y,v.z],i*3)}const geo=new THREE.BufferGeometry();geo.setAttribute("position",new THREE.BufferAttribute(positions,3));const particles=new THREE.Points(geo,new THREE.PointsMaterial({color:0x58dfff,size:.012,transparent:true,opacity:.72}));group.add(particles);
  const halo=new THREE.Mesh(new THREE.TorusGeometry(1.28,.006,4,100),new THREE.MeshBasicMaterial({color:0x48cfff,transparent:true,opacity:.38}));halo.rotation.x=1.2;group.add(halo);
  const clock=new THREE.Clock();let frame=0,blink=0,alive=true;
  const resize=()=>{if(!root.clientWidth||!root.clientHeight)return;renderer.setSize(root.clientWidth,root.clientHeight);camera.aspect=root.clientWidth/root.clientHeight;camera.updateProjectionMatrix()};const observer=new ResizeObserver(resize);observer.observe(root);
  const animate=()=>{if(!alive)return;frame=requestAnimationFrame(animate);const t=clock.getElapsedTime();group.rotation.y=Math.sin(t*.42)*.12;group.rotation.x=Math.sin(t*.7)*.035;head.scale.y=1.08+Math.sin(t*1.6)*.012;particles.rotation.y=-t*.035;halo.rotation.z=t*.12;const pulse=status==="listening"?1.16:status==="thinking"?.9:status==="speaking"?1.28:1;group.scale.setScalar(pulse*(1+Math.sin(t*(status==="speaking"?9:2))*(status==="speaking"?.018:.006)));blink=(Math.sin(t*.9)+1)/2;eyes.forEach(e=>{e.scale.y=blink>.985?.12:1});renderer.render(scene,camera)};
  animate();return()=>{alive=false;cancelAnimationFrame(frame);observer.disconnect();scene.traverse(o=>{if(o instanceof THREE.Mesh||o instanceof THREE.Points){o.geometry.dispose();const m=o.material;if(Array.isArray(m))m.forEach(x=>x.dispose());else m.dispose()}});renderer.dispose();renderer.domElement.remove()};
 },[status]);
 return <div className={"holo-face "+status} ref={host} aria-label={"VEER holographic face: "+status}/>;
}
