import SwiftUI
import KotlinModules

struct MainView: View {
    @State private var tasks: [ToDoItem] = []
    private let viewModel = ToDoViewModel()
    @State private var newTaskText: String = ""

    var body: some View {
        VStack {
            ProgressView(value: viewModel.progress)
                .padding()

            HStack {
                TextField("New Task", text: $newTaskText)
                    .textFieldStyle(RoundedBorderTextFieldStyle())
                Button("Add") {
                    if !newTaskText.isEmpty {
                        viewModel.addItem(text: newTaskText)
                        updateTasks()
                        newTaskText = ""
                    }
                }
            }
            .padding()

            List {
                ForEach(tasks, id: \.id) { task in
                    HStack {
                        Text(task.text)
                            .strikethrough(task.isDone)
                        Spacer()
                        Button(action: {
                            viewModel.toggleItemDone(id: task.id)
                            updateTasks()
                        }) {
                            Image(systemName: task.isDone ? "checkmark.circle.fill" : "circle")
                        }
                        Button(action: {
                            viewModel.removeItem(id: task.id)
                            updateTasks()
                        }) {
                            Image(systemName: "trash")
                        }
                    }
                }
            }
        }
        .onAppear {
            updateTasks()
        }
    }

    private func updateTasks() {
        tasks = viewModel.items
    }
}
