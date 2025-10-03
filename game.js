// Snake Royale: Multiplayer Game Logic

const canvas = document.getElementById('gameCanvas');
const ctx = canvas.getContext('2d');
const statusDiv = document.getElementById('status');

const CELL_SIZE = 18;
const GRID_WIDTH = Math.floor(canvas.width / CELL_SIZE);
const GRID_HEIGHT = Math.floor(canvas.height / CELL_SIZE);

let ws;
let playerId = null;
let snakes = {};
let food = [];
let gameState = 'waiting';

function connectWebSocket() {
    ws = new WebSocket('ws://localhost:8080/snake'); // Update to deployed server if needed

    ws.onopen = () => {
        statusDiv.textContent = 'Connected! Use arrow keys to control.';
    };
    ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.type === 'init') {
            playerId = msg.playerId;
            statusDiv.textContent = 'Game starting!';
        } else if (msg.type === 'update') {
            snakes = msg.snakes;
            food = msg.food;
            gameState = msg.state;
        } else if (msg.type === 'dead') {
            statusDiv.textContent = 'You died! Press R to restart.';
            gameState = 'dead';
        }
        draw();
    };
    ws.onclose = () => {
        statusDiv.textContent = 'Disconnected from server.';
    };
}

function sendDirection(dir) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({type: 'direction', dir}));
    }
}

document.addEventListener('keydown', (e) => {
    if (gameState === 'dead' && e.key.toLowerCase() === 'r') {
        ws.send(JSON.stringify({type: 'restart'}));
    }
    if (['ArrowUp', 'w'].includes(e.key)) sendDirection('up');
    if (['ArrowDown', 's'].includes(e.key)) sendDirection('down');
    if (['ArrowLeft', 'a'].includes(e.key)) sendDirection('left');
    if (['ArrowRight', 'd'].includes(e.key)) sendDirection('right');
});

function draw() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Draw food
    food.forEach(f => {
        ctx.fillStyle = '#f9d423';
        ctx.fillRect(f.x * CELL_SIZE, f.y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
    });

    // Draw snakes
    Object.entries(snakes).forEach(([id, snake]) => {
        if (snake.cells.length === 0) return;
        ctx.strokeStyle = (id === playerId) ? '#56d364' : '#fff';
        ctx.lineWidth = 2;
        ctx.beginPath();
        let head = snake.cells[0];
        ctx.moveTo(head.x * CELL_SIZE + CELL_SIZE / 2, head.y * CELL_SIZE + CELL_SIZE / 2);
        snake.cells.forEach(cell => {
            ctx.lineTo(cell.x * CELL_SIZE + CELL_SIZE / 2, cell.y * CELL_SIZE + CELL_SIZE / 2);
        });
        ctx.stroke();

        // Draw snake head
        ctx.fillStyle = (id === playerId) ? '#56d364' : '#fff';
        ctx.beginPath();
        ctx.arc(head.x * CELL_SIZE + CELL_SIZE / 2, head.y * CELL_SIZE + CELL_SIZE / 2, CELL_SIZE / 2, 0, 2 * Math.PI);
        ctx.fill();
    });
}

connectWebSocket();