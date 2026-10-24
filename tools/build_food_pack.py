#!/usr/bin/env python3
"""Builds the offline food pack (packs/food_usda_sr.json) from USDA FoodData Central SR Legacy CSVs.

Usage: build_food_pack.py <extracted SR Legacy csv dir> <output json>
Values are copied per 100 g exactly as published; nothing is estimated here. Portions come from
food_portion.csv (household measures with gram weights). SR Legacy is public domain (CC0 1.0).
"""
import csv, json, os, sys

src, out = sys.argv[1], sys.argv[2]
# Locate the CSV folder (the zip nests files in a dated directory).
for root, _, files in os.walk(src):
    if "food.csv" in files and "food_nutrient.csv" in files:
        src = root
        break

# FDC nutrient ids -> app nutrient names (scanner/nutrition-engine Nutrient enum).
NUTRIENTS = {
    "1008": "ENERGY_KCAL", "1062": "ENERGY_KJ", "1003": "PROTEIN", "1004": "FAT", "1005": "CARBOHYDRATE",
    "1079": "FIBRE", "2000": "SUGARS", "1093": "SODIUM", "1258": "SATURATED_FAT", "1253": "CHOLESTEROL", "1257": "TRANS_FAT",
}

def rows(name):
    with open(os.path.join(src, name), newline="", encoding="utf-8") as f:
        yield from csv.DictReader(f)

foods = {}
for r in rows("food.csv"):
    if r.get("data_type", "sr_legacy_food") in ("sr_legacy_food", ""):
        foods[r["fdc_id"]] = {"id": "usda-" + r["fdc_id"], "name": r["description"].strip(), "source": "USDA FoodData Central SR Legacy",
                              "sourceId": r["fdc_id"], "per100g": {}, "portions": [], "labels": []}

def clean(v):
    s = ("%.3f" % float(v)).rstrip("0").rstrip(".")
    return s if s not in ("", "-0") else "0"

for r in rows("food_nutrient.csv"):
    f = foods.get(r["fdc_id"])
    key = NUTRIENTS.get(r["nutrient_id"])
    if f is not None and key and r.get("amount", "") != "":
        f["per100g"][key] = clean(r["amount"])

if os.path.exists(os.path.join(src, "food_portion.csv")):
    for r in rows("food_portion.csv"):
        f = foods.get(r["fdc_id"])
        if f is None or not r.get("gram_weight"):
            continue
        amount = r.get("amount", "").strip()
        desc = (r.get("modifier") or r.get("portion_description") or "").strip()
        if not desc:
            continue
        label = (clean(amount) + " " if amount else "") + desc
        f["portions"].append({"label": label, "grams": clean(r["gram_weight"])})

kept = [f for f in foods.values() if "ENERGY_KCAL" in f["per100g"]]
kept.sort(key=lambda f: f["name"].lower())
pack = {"packId": "food-usda-sr-legacy", "version": "2018-04", "source": "USDA FoodData Central SR Legacy (April 2018)",
        "license": "Public domain (CC0 1.0)", "foods": kept}
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w", encoding="utf-8") as f:
    json.dump(pack, f, ensure_ascii=False, separators=(",", ":"))
print(f"food pack: {len(kept)} foods with energy values (of {len(foods)}), {os.path.getsize(out)} bytes -> {out}")
