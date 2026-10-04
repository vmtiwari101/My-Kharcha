import os
import re
import subprocess

indian_mapping = {
    "apb.svg": "ic_bank_airtel.xml",
    "ausfb.svg": "ic_bank_au.xml",
    "axis.svg": "ic_bank_axis.xml",
    "bandhan.svg": "ic_bank_bandhan.xml",
    "bob.svg": "ic_bank_bob.xml",
    "boi.svg": "ic_bank_boi.xml",
    "bom.svg": "ic_bank_bom.xml",
    "canara.svg": "ic_bank_canara.xml",
    "cbi.svg": "ic_bank_cbi.xml",
    "city.svg": "ic_bank_city.xml",
    "csb.svg": "ic_bank_csb.xml",
    "cub.svg": "ic_bank_cub.xml",
    "dcb.svg": "ic_bank_dcb.xml",
    "dhanlaxmi.svg": "ic_bank_dhanlaxmi.xml",
    "esaf.svg": "ic_bank_esaf.xml",
    "federal.svg": "ic_bank_federal.xml",
    "fino.svg": "ic_bank_fino.xml",
    "hdfc.svg": "ic_bank_hdfc.xml",
    "icici.svg": "ic_bank_icici.xml",
    "idbi.svg": "ic_bank_idbi.xml",
    "idfc.svg": "ic_bank_idfc.xml",
    "indian.svg": "ic_bank_indian.xml",
    "indiapost.svg": "ic_bank_ippb.xml",
    "indus.svg": "ic_bank_indusind.xml",
    "iob.svg": "ic_bank_iob.xml",
    "jio.svg": "ic_bank_jio.xml",
    "jnk.svg": "ic_bank_jnk.xml",
    "karnataka.svg": "ic_bank_karnataka.xml",
    "kotak.svg": "ic_bank_kotak.xml",
    "kvb.svg": "ic_bank_kvb.xml",
    "ntb.svg": "ic_bank_ntb.xml",
    "paytm.svg": "ic_bank_paytm.xml",
    "pnb.svg": "ic_bank_pnb.xml",
    "psb.svg": "ic_bank_psb.xml",
    "rbl.svg": "ic_bank_rbl.xml",
    "sbi.svg": "ic_bank_sbi.xml",
    "sib.svg": "ic_bank_sib.xml",
    "tmb.svg": "ic_bank_tmb.xml",
    "ubi.svg": "ic_bank_union.xml",
    "uco.svg": "ic_bank_uco.xml",
    "ujjivan.svg": "ic_bank_ujjivan.xml",
    "yes.svg": "ic_bank_yes.xml",
}

intl_mapping = {
    "citi.svg": "ic_bank_citi.xml",
    "hsbc.svg": "ic_bank_hsbc.xml",
    "standard.svg": "ic_bank_standard_chartered.xml",
    "dbs.svg": "ic_bank_dbs.xml",
    "barclays.svg": "ic_bank_barclays.xml",
    "american-express.svg": "ic_bank_amex.xml",
    "chase.svg": "ic_bank_chase.xml",
    "boa.svg": "ic_bank_boa.xml",
}

indian_dir = "/tmp/logos/global-bank-logos-main/assets/bank/indian-bank"
intl_dir = "/tmp/logos/global-bank-logos-main/assets/bank/international-bank"
output_dir = "/app/applet/app/src/main/res/drawable"

os.makedirs(output_dir, exist_ok=True)
os.makedirs("/tmp/cleaned_svg", exist_ok=True)

imported_count = 0
replaced_count = 0

def clean_svg_content(content):
    # Replace fill="white" / fill='white'
    content = re.sub(r'fill\s*=\s*["\']white["\']', 'fill="#FFFFFF"', content, flags=re.IGNORECASE)
    # Replace stroke="white" / stroke='white'
    content = re.sub(r'stroke\s*=\s*["\']white["\']', 'stroke="#FFFFFF"', content, flags=re.IGNORECASE)
    # Replace fill="black" / fill='black'
    content = re.sub(r'fill\s*=\s*["\']black["\']', 'fill="#000000"', content, flags=re.IGNORECASE)
    # Replace stroke="black" / stroke='black'
    content = re.sub(r'stroke\s*=\s*["\']black["\']', 'stroke="#000000"', content, flags=re.IGNORECASE)
    return content

# 1. Process Indian Banks
for svg_name, xml_name in indian_mapping.items():
    src_path = os.path.join(indian_dir, svg_name)
    if not os.path.exists(src_path):
        print(f"Warning: Source file not found {src_path}")
        continue
    
    with open(src_path, "r", encoding="utf-8") as f:
        svg_content = f.read()
    
    cleaned_content = clean_svg_content(svg_content)
    temp_path = os.path.join("/tmp/cleaned_svg", svg_name)
    with open(temp_path, "w", encoding="utf-8") as f:
        f.write(cleaned_content)
    
    dst_path = os.path.join(output_dir, xml_name)
    is_replacement = os.path.exists(dst_path)
    
    # Run svg2vectordrawable
    result = subprocess.run(["npx", "svg2vectordrawable", "-i", temp_path, "-o", dst_path], capture_output=True, text=True)
    if result.returncode == 0 and os.path.exists(dst_path):
        imported_count += 1
        if is_replacement:
            replaced_count += 1
        print(f"Successfully processed {svg_name} -> {xml_name}")
    else:
        print(f"Error processing {svg_name}: {result.stderr}")

# 2. Process International Banks
for svg_name, xml_name in intl_mapping.items():
    src_path = os.path.join(intl_dir, svg_name)
    if not os.path.exists(src_path):
        print(f"Warning: Source file not found {src_path}")
        continue
    
    with open(src_path, "r", encoding="utf-8") as f:
        svg_content = f.read()
    
    cleaned_content = clean_svg_content(svg_content)
    temp_path = os.path.join("/tmp/cleaned_svg", svg_name)
    with open(temp_path, "w", encoding="utf-8") as f:
        f.write(cleaned_content)
    
    dst_path = os.path.join(output_dir, xml_name)
    is_replacement = os.path.exists(dst_path)
    
    # Run svg2vectordrawable
    result = subprocess.run(["npx", "svg2vectordrawable", "-i", temp_path, "-o", dst_path], capture_output=True, text=True)
    if result.returncode == 0 and os.path.exists(dst_path):
        imported_count += 1
        if is_replacement:
            replaced_count += 1
        print(f"Successfully processed {svg_name} -> {xml_name}")
    else:
        print(f"Error processing {svg_name}: {result.stderr}")

print(f"Total assets processed: {imported_count}")
print(f"Placeholder assets replaced: {replaced_count}")
