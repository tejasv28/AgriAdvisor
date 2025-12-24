from langchain.tools import tool
import wikipedia
from duckduckgo_search import DDGS

@tool
def wiki_tool(query: str) -> str:
    """Search for information on Wikipedia."""
    try:
        # Get the summary, limited to 2 sentences
        return wikipedia.summary(query, sentences=2)
    except Exception as e:
        return f"Error: {str(e)}"

@tool
def search_tool(query: str) -> str:
    """Search the web using DuckDuckGo."""
    try:
        # Get 2 search results
        results = DDGS().text(query, max_results=2)
        if not results:
            return "No web results found."
        # Format the results into a single string
        return "\n".join([f"{r['title']}: {r['body']}" for r in results])
    except Exception as e:
        return f"Error: {str(e)}"

@tool
def save_tool(data: str, filename: str = "output.txt") -> str:
    """Save given data into a file."""
    try:
        with open(filename, "w", encoding="utf-8") as f:
            f.write(data)
        return f"Data saved to {filename}"
    except Exception as e:
        return f"Error: {str(e)}"
