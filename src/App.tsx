import { useState } from 'react'
import { TopBar } from 'src/components/TopBar'
import { BottomNav } from 'src/components/BottomNav'
import { AddTitleWorkflow } from 'src/components/AddTitleWorkflow'
import { Library } from 'src/views/Library'
import { Ledger } from 'src/views/Ledger'

type AppView = 'library' | 'ledger'

export default function App() {
  const [currentView, setCurrentView] = useState<AppView>('library')

  return (
    <div className="min-h-screen bg-void grain vignette">
      <TopBar currentView={currentView} onViewChange={setCurrentView} />

      <main className="pt-0 sm:pb-0 pb-16">
        {currentView === 'library' && <Library />}
        {currentView === 'ledger' && <Ledger />}
      </main>

      <BottomNav currentView={currentView} onViewChange={setCurrentView} />
      <AddTitleWorkflow />
    </div>
  )
}
