import { createRouter, createWebHistory } from 'vue-router'

import HomeView from '../views/HomeView.vue'

const ListView = () => import('../views/ListView.vue')
const NoteView = () => import('../views/NoteView.vue')
const SettingsView = () => import('../views/SettingsView.vue')
const TasksView = () => import('../views/TasksView.vue')
const HabitsView = () => import('../views/HabitsView.vue')
const SearchView = () => import('../views/SearchView.vue')
const AgendaView = () => import('../views/AgendaView.vue')

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'home', component: HomeView, meta: { keepAlive: true } },
    { path: '/note/:id?', component: NoteView },

    { path: '/lists', name: 'lists', component: ListView },
    { path: '/list/:id', name: 'list-detail', component: ListView },
    { path: '/tasks', name: 'tasks', component: TasksView, meta: { keepAlive: true } },
    { path: '/habits', name: 'habits', component: HabitsView, meta: { keepAlive: true } },
    { path: '/task-list/:id', name: 'task-detail', component: ListView },

    { path: '/settings', component: SettingsView },
    { path: '/search', name: 'search', component: SearchView },
    { path: '/agenda', name: 'agenda', component: AgendaView },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

export default router
