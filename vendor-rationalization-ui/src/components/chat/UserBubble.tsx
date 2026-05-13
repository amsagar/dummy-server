import type { FC } from "react";

type Props = { content: string };

const UserBubble: FC<Props> = ({ content }) => (
  <div className="flex justify-end">
    <div className="max-w-[82%] rounded-2xl rounded-tr-sm px-4 py-2.5 text-sm text-white" style={{ background: "#005CB9" }}>
      {content}
    </div>
  </div>
);

export default UserBubble;
