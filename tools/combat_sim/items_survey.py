import glob, os, re, collections
import xml.etree.ElementTree as ET
import l2data
rows=[]
for path in sorted(glob.glob(os.path.join(l2data.DATA,"stats/items/*.xml"))):
    for it in ET.parse(path).getroot().iter("item"):
        if it.get("type")!="Weapon": continue
        sets={s.get("name"):s.get("val") for s in it.findall("set")}
        st={s.get("type"):float(s.text) for s in it.findall("stats/stat")}
        rows.append(dict(id=int(it.get("id")),name=it.get("name"),wtype=sets.get("weapon_type"),body=sets.get("bodypart"),
            grade=sets.get("crystal_type","NONE"),magic=sets.get("is_magic_weapon")=="true",
            tradable=sets.get("is_tradable","true"),dropable=sets.get("is_dropable","true"),
            pAtk=st.get("pAtk",0),mAtk=st.get("mAtk",0),spd=st.get("pAtkSpd",0),crit=st.get("critRate",0),
            ss=sets.get("soulshots"),sps=sets.get("spiritshots"),price=int(sets.get("price","0") or 0)))
print(len(rows),"weapons")
groups=collections.defaultdict(list)
for r in rows: groups[(r["wtype"],r["body"],r["grade"])].append(r)
order={"NONE":0,"D":1,"C":2,"B":3,"A":4,"S":5}
for k in sorted(groups,key=lambda k:(k[0] or "",k[1] or "",order.get(k[2],9))):
    g=sorted(groups[k],key=lambda r:-(r["pAtk"]+r["mAtk"]*0.01))[:2]
    print(k,len(groups[k]),[(r["name"],r["pAtk"],r["mAtk"],r["spd"],r["crit"]) for r in g])
