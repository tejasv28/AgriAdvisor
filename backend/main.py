import os
import json
import re
import httpx
import asyncio
from typing import Optional
from fastapi import FastAPI, Form, UploadFile, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from dotenv import load_dotenv

basedir = os.path.abspath(os.path.dirname(__file__))
env_path = os.path.join(basedir, '.env')
load_dotenv(dotenv_path=env_path, override=True)

PERPLEXITY_API_KEY = os.getenv("PERPLEXITY_API_KEY")
HF_API_KEY = os.getenv("HUGGINGFACE_API_KEY")

app = FastAPI(title="Kisan Mitra Backend")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

def extract_clean_json(text_response: str):
    if not text_response: return {"summary": "No response."}
    cleaned = text_response.replace("```json", "").replace("```", "").strip()
    try:
        json_match = re.search(r"(\{.*\})", cleaned, re.DOTALL)
        if json_match:
            parsed = json.loads(json_match.group(1))
            if "summary" in parsed:
                parsed["summary"] = parsed["summary"].replace("**", "").replace("### ", "").replace("###", "").strip()
            return parsed
        return {"summary": cleaned}
    except:
        return {"summary": cleaned}

async def call_huggingface_vision(image_bytes: bytes):
    if not HF_API_KEY: return "Farm image"
    url = "https://api-inference.huggingface.co/models/microsoft/git-base"
    headers = {"Authorization": f"Bearer {HF_API_KEY}"}
    async with httpx.AsyncClient() as client:
        try:
            resp = await client.post(url, headers=headers, content=image_bytes)
            if resp.status_code == 200: return resp.json()[0].get("generated_text", "Farm image")
        except: pass
    return "Farm image"

async def call_perplexity_api(system_instruction: str, user_query: str):
    url = "https://api.perplexity.ai/chat/completions"
    payload = {
        "model": "sonar-pro", 
        "messages": [{"role": "system", "content": system_instruction}, {"role": "user", "content": user_query}],
        "temperature": 0.1
    }
    headers = {"Authorization": f"Bearer {PERPLEXITY_API_KEY}", "Content-Type": "application/json"}
    
    async with httpx.AsyncClient(timeout=60.0) as client:
        try:
            resp = await client.post(url, json=payload, headers=headers)
            if resp.status_code != 200: return {"summary": "API Error"}
            content = resp.json()['choices'][0]['message']['content']
            result = extract_clean_json(content)
            if "sources" not in result: result["sources"] = []
            return result
        except Exception as e:
            return {"summary": "Connection failed."}

@app.post("/chat")
async def chat(query: str = Form(...), language: str = Form("en"), image: Optional[UploadFile] = None):
    print(f"📩 Query: '{query}' | Lang: '{language}'")
    
    lang_map = {"hi": "Hindi", "ml": "Malayalam", "en": "English"}
    target_lang = lang_map.get(language, "English")
    
    final_query = query
    if image:
        img_data = await image.read()
        desc = await call_huggingface_vision(img_data)
        final_query = f"IMAGE SHOWS: {desc}. QUESTION: {query}"

    # --- UPDATED: STRICT SCRIPT ENFORCEMENT PROMPT ---
    
    if "TRANSLATE_THIS_TEXT" in query:
        # Translation Mode
        text_to_translate = query.replace("TRANSLATE_THIS_TEXT:", "")
        system_instruction = f"""
        You are a translation engine.
        Target Language: {target_lang}
        
        STRICT RULES:
        1. Keep the headers "CONCEPT:", "HOW IT WORKS:", "SOLUTION:" exactly in ENGLISH.
        2. Translate ALL content text into {target_lang} script.
        3. Do NOT explain the translation. Just translate.
        4. Do NOT use English alphabets in the body text.
        
        Example Output format:
        CONCEPT:
        (Text in {target_lang})
        
        HOW IT WORKS:
        - (Point in {target_lang})
        """
        final_query = text_to_translate
    else:
        # Advisor Mode
        system_instruction = f"""
        You are Kisan Mitra, a village agricultural expert who speaks {target_lang} fluently.
        
        TASK:
        Answer the farmer's question using **{target_lang} Script ONLY**.
        
        FORMATTING RULES (Follow Strictly):
        1. You MUST use these exact English Headers: 'CONCEPT:', 'HOW IT WORKS:', 'SOLUTION:'.
        2. The content UNDER the headers must be in {target_lang}.
        3. NO English sentences in the explanation.
        
        CORRECT EXAMPLE (if Language is Malayalam):
        CONCEPT:
        തുള്ളിനന എന്നത് ചെടികളുടെ ചുവട്ടിൽ...
        
        INCORRECT EXAMPLE:
        CONCEPT:
        Thullinana is a method... (This is wrong!)
        
        Output JSON: {{ "topic": "Title", "summary": "Your structured response", "sources": [] }}
        """

    return await call_perplexity_api(system_instruction, final_query)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="127.0.0.1", port=8000, reload=True)